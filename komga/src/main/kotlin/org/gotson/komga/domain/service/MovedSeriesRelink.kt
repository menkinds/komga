package org.gotson.komga.domain.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.Book
import org.gotson.komga.domain.model.Series
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.SeriesMetadataRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.gotson.komga.infrastructure.hash.Hasher
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.net.URL

private val logger = KotlinLogging.logger {}

/**
 * Keeps the existing series and book rows when a folder is renamed or moved.
 * Upstream restore copies data onto brand-new ids and only after every hash is already known.
 */
@Service
class MovedSeriesRelink(
  private val seriesRepository: SeriesRepository,
  private val bookRepository: BookRepository,
  private val seriesMetadataRepository: SeriesMetadataRepository,
  private val hasher: Hasher,
  private val hashArchive: HashArchive,
  private val takenOverSeriesCleanup: TakenOverSeriesCleanup,
  private val transactionTemplate: TransactionTemplate,
  @Lazy private val bookLifecycle: BookLifecycle,
  @Lazy private val seriesLifecycle: SeriesLifecycle,
) {
  data class RelinkResult(
    val series: Series,
    val booksToAdd: List<Book>,
  )

  fun tryRelink(
    newSeries: Series,
    newBooks: List<Book>,
  ): RelinkResult? {
    if (newSeries.libraryId.isBlank() || newBooks.isEmpty()) return null
    val deleted =
      seriesRepository
        .findAllByLibraryId(newSeries.libraryId)
        .filter { it.deletedDate != null }
        .map { it to bookRepository.findAllBySeriesId(it.id) }
    if (deleted.isEmpty()) return null

    val hashedDeleted = deleted.filter { (_, books) -> books.any { it.fileHash.isNotBlank() } }
    if (hashedDeleted.isNotEmpty()) {
      val newHashes = newBooks.map { hashOf(it) }
      val ranked =
        hashedDeleted.mapNotNull { (series, books) ->
          val matched = overlapCount(newHashes, books.map { it.fileHash })
          if (mostly(matched, newBooks.size, books.size)) series to books to matched else null
        }
      val best = ranked.maxByOrNull { it.second }
      if (best != null && ranked.count { it.second == best.second } == 1) {
        val (series, books) = best.first
        return relinkByHash(series, books, newSeries, newBooks, newHashes)
      }
    }

    val unhashed = deleted.filter { (_, books) -> books.none { it.fileHash.isNotBlank() } }
    val key = SeriesNameNormalizer.normalize(newSeries.name)
    if (key.isEmpty()) return null
    val nameMatches = unhashed.filter { (series, _) -> SeriesNameNormalizer.normalize(series.name) == key }
    if (nameMatches.size != 1) return null
    val (series, books) = nameMatches.first()
    return relinkByName(series, books, newSeries, newBooks)
  }

  /**
   * Move trashed books with the same hash onto [series] instead of inserting duplicates.
   * Returns the scanned URLs that were absorbed.
   */
  fun reuseDeletedBooks(
    series: Series,
    scanned: List<Book>,
  ): Set<URL> {
    val reused = mutableSetOf<URL>()
    scanned.forEach { candidate ->
      val sized = bookRepository.findAllDeletedByFileSize(candidate.fileSize).filter { it.fileHash.isNotBlank() && it.libraryId == series.libraryId }
      if (sized.isEmpty()) return@forEach
      val hash = hashOf(candidate)
      val match = sized.find { it.fileHash == hash } ?: return@forEach
      bookRepository.update(match.adopt(candidate, series, hash))
      reused.add(candidate.url)
    }
    return reused
  }

  /**
   * Called after a file hash is stored. Hashes are often computed only after the scan that saw the move.
   */
  fun onBookHashed(book: Book) {
    val fresh = bookRepository.findByIdOrNull(book.id) ?: return
    if (fresh.fileHash.isBlank()) return
    if (fresh.deletedDate != null) {
      hashArchive.archive(fresh)
      foldDeletedSeries(fresh.seriesId)
      return
    }
    if (!foldLiveSeries(fresh.seriesId)) takenOverSeriesCleanup.markRestored(fresh, hashArchive.restore(fresh))
  }

  private fun relinkByHash(
    deletedSeries: Series,
    deletedBooks: Collection<Book>,
    newSeries: Series,
    newBooks: List<Book>,
    newHashes: List<String>,
  ): RelinkResult {
    val pool = deletedBooks.toMutableList()
    val pairs = mutableListOf<Pair<Book, Book>>()
    val unmatched = mutableListOf<Book>()
    newBooks.forEachIndexed { index, scanned ->
      val hash = newHashes[index]
      val found = if (hash.isBlank()) null else pool.indexOfFirst { it.fileHash == hash }.takeIf { it >= 0 }
      if (found == null) {
        unmatched.add(scanned)
      } else {
        pairs.add(pool.removeAt(found) to scanned)
      }
    }
    val series = commitRelink(deletedSeries, newSeries, pairs) { old, scanned -> old.adopt(scanned, deletedSeries, scannedHash(newBooks, newHashes, scanned)) }
    logger.info { "Relinked series ${series.id} by file hash to ${series.url}" }
    return RelinkResult(series, unmatched)
  }

  private fun relinkByName(
    deletedSeries: Series,
    deletedBooks: Collection<Book>,
    newSeries: Series,
    newBooks: List<Book>,
  ): RelinkResult {
    val pool = deletedBooks.toMutableList()
    val pairs = mutableListOf<Pair<Book, Book>>()
    val unmatched = mutableListOf<Book>()
    newBooks.forEach { scanned ->
      val byName = pool.indexOfFirst { it.name == scanned.name }.takeIf { it >= 0 }
      val bySize =
        if (byName != null || scanned.fileSize <= 0)
          null
        else
          pool
            .withIndex()
            .filter { it.value.fileSize == scanned.fileSize }
            .singleOrNull()
            ?.index
      val found = byName ?: bySize
      if (found == null) unmatched.add(scanned) else pairs.add(pool.removeAt(found) to scanned)
    }
    val series = commitRelink(deletedSeries, newSeries, pairs) { old, scanned -> old.adopt(scanned, deletedSeries, old.fileHash) }
    logger.info { "Relinked series ${series.id} by normalized name to ${series.url}" }
    return RelinkResult(series, unmatched)
  }

  private fun commitRelink(
    deletedSeries: Series,
    newSeries: Series,
    pairs: List<Pair<Book, Book>>,
    adopt: (Book, Book) -> Book,
  ): Series {
    transactionTemplate.executeWithoutResult {
      if (pairs.isNotEmpty()) bookRepository.update(pairs.map { (old, scanned) -> adopt(old, scanned) })
      val metadata = seriesMetadataRepository.findByIdOrNull(deletedSeries.id)
      if (metadata != null) {
        seriesMetadataRepository.update(
          metadata.copy(
            title = if (metadata.titleLock) metadata.title else newSeries.name,
            titleSort = if (metadata.titleSortLock) metadata.titleSort else newSeries.name,
          ),
        )
      }
      seriesRepository.update(
        deletedSeries.copy(
          name = newSeries.name,
          url = newSeries.url,
          fileLastModified = newSeries.fileLastModified,
          deletedDate = null,
          oneshot = newSeries.oneshot,
        ),
      )
    }
    return seriesRepository.findByIdOrNull(deletedSeries.id) ?: deletedSeries
  }

  private fun foldLiveSeries(liveSeriesId: String): Boolean {
    val live = seriesRepository.findByIdOrNull(liveSeriesId) ?: return false
    if (live.deletedDate != null) return false
    val liveBooks = bookRepository.findAllBySeriesId(live.id).filter { it.deletedDate == null }
    if (liveBooks.isEmpty() || liveBooks.any { it.fileHash.isBlank() }) return false
    val match = bestDeletedMatch(live.libraryId, liveBooks) ?: return false
    absorb(match, live, liveBooks)
    return true
  }

  private fun foldDeletedSeries(deletedSeriesId: String): Boolean {
    val deleted = seriesRepository.findByIdOrNull(deletedSeriesId) ?: return false
    if (deleted.deletedDate == null) return false
    val deletedBooks = bookRepository.findAllBySeriesId(deleted.id)
    val hashed = deletedBooks.filter { it.fileHash.isNotBlank() }
    if (hashed.isEmpty() || hashed.size * 2 <= deletedBooks.size) return false
    val liveSeries =
      seriesRepository
        .findAllByLibraryId(deleted.libraryId)
        .filter { it.deletedDate == null }
    val ranked =
      liveSeries.mapNotNull { live ->
        val liveBooks = bookRepository.findAllBySeriesId(live.id).filter { it.deletedDate == null }
        if (liveBooks.isEmpty() || liveBooks.any { it.fileHash.isBlank() }) return@mapNotNull null
        val matched = overlapCount(liveBooks.map { it.fileHash }, deletedBooks.map { it.fileHash })
        if (!mostly(matched, liveBooks.size, deletedBooks.size)) return@mapNotNull null
        Triple(live, liveBooks, matched)
      }
    val best = ranked.maxByOrNull { it.third } ?: return false
    if (ranked.count { it.third == best.third } != 1) return false
    absorb(deleted, best.first, best.second)
    return true
  }

  private fun bestDeletedMatch(
    libraryId: String,
    liveBooks: List<Book>,
  ): Series? {
    val ranked =
      seriesRepository
        .findAllByLibraryId(libraryId)
        .filter { it.deletedDate != null }
        .mapNotNull { deleted ->
          val books = bookRepository.findAllBySeriesId(deleted.id)
          val matched = overlapCount(liveBooks.map { it.fileHash }, books.map { it.fileHash })
          if (mostly(matched, liveBooks.size, books.size)) deleted to matched else null
        }
    val best = ranked.maxByOrNull { it.second } ?: return null
    if (ranked.count { it.second == best.second } != 1) return null
    return best.first
  }

  private fun absorb(
    deleted: Series,
    live: Series,
    liveBooks: List<Book>,
  ) {
    logger.info { "Absorbing series ${live.id} into trashed series ${deleted.id}" }
    val deletedBooks = bookRepository.findAllBySeriesId(deleted.id).toMutableList()
    val updates = mutableListOf<Book>()
    val duplicates = mutableListOf<Book>()
    val moved = mutableListOf<Book>()
    liveBooks.forEach { liveBook ->
      val index = deletedBooks.indexOfFirst { it.fileHash.isNotBlank() && it.fileHash == liveBook.fileHash }
      if (index >= 0) {
        val old = deletedBooks.removeAt(index)
        updates.add(old.adopt(liveBook, deleted, liveBook.fileHash))
        duplicates.add(liveBook)
      } else {
        moved.add(liveBook.copy(seriesId = deleted.id))
      }
    }
    transactionTemplate.executeWithoutResult {
      bookLifecycle.deleteMany(duplicates, archive = false)
      if (updates.isNotEmpty()) bookRepository.update(updates)
      if (moved.isNotEmpty()) bookRepository.update(moved)
      val metadata = seriesMetadataRepository.findByIdOrNull(deleted.id)
      if (metadata != null) {
        seriesMetadataRepository.update(
          metadata.copy(
            title = if (metadata.titleLock) metadata.title else live.name,
            titleSort = if (metadata.titleSortLock) metadata.titleSort else live.name,
          ),
        )
      }
      seriesRepository.update(
        deleted.copy(
          name = live.name,
          url = live.url,
          fileLastModified = live.fileLastModified,
          deletedDate = null,
          oneshot = live.oneshot,
        ),
      )
      val leftovers = bookRepository.findAllBySeriesId(live.id)
      if (leftovers.isNotEmpty()) bookRepository.update(leftovers.map { it.copy(seriesId = deleted.id) })
    }
    seriesRepository.findByIdOrNull(live.id)?.let { seriesLifecycle.deleteMany(listOf(it)) }
    seriesRepository.findByIdOrNull(deleted.id)?.let { seriesLifecycle.sortBooks(it) }
  }

  private fun hashOf(book: Book): String {
    if (book.fileHash.isNotBlank()) return book.fileHash
    return try {
      hasher.computeHash(book.path)
    } catch (e: Exception) {
      logger.warn(e) { "Could not hash ${book.url} while relinking" }
      ""
    }
  }

  private fun scannedHash(
    books: List<Book>,
    hashes: List<String>,
    scanned: Book,
  ): String = hashes.getOrElse(books.indexOf(scanned)) { scanned.fileHash }

  private fun Book.adopt(
    scanned: Book,
    series: Series,
    hash: String,
  ): Book =
    copy(
      name = scanned.name,
      url = scanned.url,
      fileLastModified = scanned.fileLastModified,
      fileSize = scanned.fileSize,
      fileHash = hash.ifBlank { fileHash },
      fileHashKoreader = fileHashKoreader.ifBlank { scanned.fileHashKoreader },
      deletedDate = null,
      oneshot = scanned.oneshot,
      seriesId = series.id,
      libraryId = series.libraryId.ifBlank { libraryId },
    )

  private fun overlapCount(
    left: Collection<String>,
    right: Collection<String>,
  ): Int {
    val pool = right.filter { it.isNotBlank() }.toMutableList()
    var count = 0
    left.forEach { hash ->
      if (hash.isBlank()) return@forEach
      val index = pool.indexOf(hash)
      if (index >= 0) {
        pool.removeAt(index)
        count++
      }
    }
    return count
  }

  private fun mostly(
    matched: Int,
    leftCount: Int,
    rightCount: Int,
  ): Boolean = matched > 0 && matched * 2 > leftCount && matched * 2 > rightCount
}
