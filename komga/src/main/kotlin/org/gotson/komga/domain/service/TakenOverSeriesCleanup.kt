package org.gotson.komga.domain.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.Book
import org.gotson.komga.domain.model.BookMetadata
import org.gotson.komga.domain.model.SearchCondition
import org.gotson.komga.domain.model.SearchContext
import org.gotson.komga.domain.model.SearchOperator
import org.gotson.komga.domain.model.Series
import org.gotson.komga.domain.persistence.BookMetadataRepository
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.LibraryRepository
import org.gotson.komga.domain.persistence.ReadProgressRepository
import org.gotson.komga.domain.persistence.SeriesCollectionRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.gotson.komga.infrastructure.hash.Hasher
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Lazy
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime

private val logger = KotlinLogging.logger {}

/**
 * Fork-only. Marks trashed books whose history was reattached to a live book by file hash,
 * and deletes a trashed series once every one of its books was taken over.
 * A trashed series with any book that was not taken over stays in the trash.
 */
@Service
class TakenOverSeriesCleanup(
  @Qualifier("dslContextRW") private val dsl: DSLContext,
  private val bookRepository: BookRepository,
  private val seriesRepository: SeriesRepository,
  private val libraryRepository: LibraryRepository,
  private val bookMetadataRepository: BookMetadataRepository,
  private val readProgressRepository: ReadProgressRepository,
  private val collectionRepository: SeriesCollectionRepository,
  private val hasher: Hasher,
  private val hashArchive: HashArchive,
  private val transactionTemplate: TransactionTemplate,
  @Lazy private val seriesLifecycle: SeriesLifecycle,
) {
  private val table = DSL.table(DSL.name("BOOK_TAKEOVER"))
  private val oldBookIdField = DSL.field(DSL.name("OLD_BOOK_ID"), SQLDataType.VARCHAR)
  private val oldSeriesIdField = DSL.field(DSL.name("OLD_SERIES_ID"), SQLDataType.VARCHAR)
  private val newBookIdField = DSL.field(DSL.name("NEW_BOOK_ID"), SQLDataType.VARCHAR)
  private val createdField = DSL.field(DSL.name("CREATED_DATE"), SQLDataType.VARCHAR)
  private val bookTable = DSL.table(DSL.name("BOOK"))
  private val bookIdField = DSL.field(DSL.name("ID"), SQLDataType.VARCHAR)
  private val bookSizeField = DSL.field(DSL.name("FILE_SIZE"), SQLDataType.BIGINT)
  private val bookDeletedField = DSL.field(DSL.name("DELETED_DATE"), SQLDataType.LOCALDATETIME)
  private val seriesTable = DSL.table(DSL.name("SERIES"))
  private val seriesIdField = DSL.field(DSL.name("ID"), SQLDataType.VARCHAR)

  fun markTakenOver(
    old: Book,
    new: Book,
  ) {
    if (old.id == new.id) return
    logger.info { "Book ${old.id} of series ${old.seriesId} taken over by book ${new.id}" }
    transactionTemplate.executeWithoutResult {
      dsl.deleteFrom(table).where(oldBookIdField.eq(old.id)).execute()
      dsl
        .insertInto(table)
        .set(oldBookIdField, old.id)
        .set(oldSeriesIdField, old.seriesId)
        .set(newBookIdField, new.id)
        .set(createdField, LocalDateTime.now().toString())
        .execute()
    }
  }

  /**
   * Call after [HashArchive.restore] put a snapshot onto [live].
   * Marks the trashed books that the snapshot came from.
   */
  fun markRestored(
    live: Book,
    snapshot: HashArchive.Snapshot?,
  ) {
    if (snapshot == null || live.fileHash.isBlank()) return
    bookRepository
      .findAllDeletedByFileSize(live.fileSize)
      .filter { it.fileHash == live.fileHash && it.seriesId == snapshot.seriesId }
      .forEach { markTakenOver(it, live) }
  }

  /**
   * Run at the end of a library scan.
   * The scan of the old library can come after the new books were added (in any library),
   * so try the reattach again first, then delete the series that were fully taken over.
   */
  fun afterScan() {
    reattachTrashedBooks()
    deleteTakenOverSeries()
  }

  private fun trashedSeries(): List<Series> =
    seriesRepository
      .findAll(SearchCondition.Deleted(SearchOperator.IsTrue), SearchContext.empty(), Pageable.unpaged())
      .content

  private fun reattachTrashedBooks() {
    val pairs =
      trashedSeries().flatMap { series ->
        val marked = takeovers(series.id).keys
        bookRepository
          .findAllBySeriesId(series.id)
          .filter { it.deletedDate != null && it.fileHash.isNotBlank() && it.id !in marked }
          .mapNotNull { old -> liveMatch(old)?.let { old to it } }
      }
    pairs.forEach { (old, live) ->
      if (isFresh(live)) {
        val snapshot = hashArchive.restore(live)
        if (snapshot != null && snapshot.seriesId == old.seriesId) markTakenOver(old, live)
      } else if (historyPresent(old, live)) {
        markTakenOver(old, live)
      }
    }
  }

  /**
   * The single live book with the same hash as [old]. Hashes live books of the same size when missing.
   */
  private fun liveMatch(old: Book): Book? {
    val ids =
      dsl
        .select(bookIdField)
        .from(bookTable)
        .where(bookSizeField.eq(old.fileSize))
        .and(bookDeletedField.isNull)
        .and(bookIdField.ne(old.id))
        .fetch(bookIdField)
    val matches =
      ids.mapNotNull { id ->
        val candidate = bookRepository.findByIdOrNull(id) ?: return@mapNotNull null
        val hashed = if (candidate.fileHash.isBlank()) hashAndStore(candidate) else candidate
        hashed?.takeIf { it.fileHash == old.fileHash }
      }
    return matches.singleOrNull()
  }

  private fun hashAndStore(book: Book): Book? =
    try {
      book.copy(fileHash = hasher.computeHash(book.path)).also { bookRepository.update(it) }
    } catch (e: Exception) {
      logger.warn(e) { "Could not hash ${book.url} while looking for a taken-over book" }
      null
    }

  /** The live book has no user data yet, so the archived history may go onto it. */
  private fun isFresh(live: Book): Boolean {
    if (readProgressRepository.findAllByBookId(live.id).isNotEmpty()) return false
    val metadata = bookMetadataRepository.findByIdOrNull(live.id) ?: return false
    return !metadata.anyLock()
  }

  /** The live book already holds the read progress and metadata of the old book. */
  private fun historyPresent(
    old: Book,
    live: Book,
  ): Boolean {
    if (!progressPresent(old, live)) return false
    val oldMeta = bookMetadataRepository.findByIdOrNull(old.id) ?: return false
    val liveMeta = bookMetadataRepository.findByIdOrNull(live.id) ?: return false
    return oldMeta.summary == liveMeta.summary &&
      oldMeta.tags == liveMeta.tags &&
      oldMeta.authors.toSet() == liveMeta.authors.toSet() &&
      oldMeta.isbn == liveMeta.isbn &&
      oldMeta.releaseDate == liveMeta.releaseDate
  }

  private fun progressPresent(
    old: Book,
    live: Book,
  ): Boolean {
    val liveUsers = readProgressRepository.findAllByBookId(live.id).map { it.userId }.toSet()
    return readProgressRepository.findAllByBookId(old.id).all { it.userId in liveUsers }
  }

  private fun deleteTakenOverSeries() {
    trashedSeries().forEach { series ->
      val takeovers = takeovers(series.id)
      if (takeovers.isEmpty()) return@forEach
      val books = bookRepository.findAllBySeriesId(series.id)
      if (books.any { it.id !in takeovers }) return@forEach
      val newBooks = takeovers.values.distinct().map { bookRepository.findByIdOrNull(it) }
      if (newBooks.any { it == null || it.deletedDate != null }) return@forEach
      val newById = newBooks.filterNotNull().associateBy { it.id }
      if (books.any { !progressPresent(it, newById.getValue(takeovers.getValue(it.id))) }) return@forEach
      val newSeriesIds = newById.values.map { it.seriesId }.distinct()
      val collectionsKept =
        collectionRepository
          .findAllContainingSeriesId(series.id, SearchContext.empty())
          .all { col -> newSeriesIds.any { it in col.seriesIds } }
      if (!collectionsKept) return@forEach

      seriesLifecycle.deleteMany(listOf(series))
      dsl.deleteFrom(table).where(oldSeriesIdField.eq(series.id)).execute()
      // the delete archived the old rows; point the archive back at the live books
      hashArchive.archiveAll(newById.values)

      val newLibraryIds = newById.values.map { it.libraryId }.distinct()
      logger.info {
        "Deleted taken-over series: oldSeriesId=${series.id}, oldLibrary=${libraryLabel(series.libraryId)}, " +
          "newSeriesId=${newSeriesIds.joinToString(",")}, newLibrary=${newLibraryIds.joinToString(",") { libraryLabel(it) }}, " +
          "books=${(books.map { it.id } + takeovers.keys).distinct().size}"
      }
    }
    // markers of series that were deleted another way (empty trash, upstream restore)
    dsl
      .deleteFrom(table)
      .where(oldSeriesIdField.notIn(dsl.select(seriesIdField).from(seriesTable)))
      .execute()
  }

  private fun takeovers(seriesId: String): Map<String, String> =
    dsl
      .select(oldBookIdField, newBookIdField)
      .from(table)
      .where(oldSeriesIdField.eq(seriesId))
      .fetch()
      .associate { it.value1()!! to it.value2()!! }

  private fun libraryLabel(libraryId: String): String = libraryRepository.findByIdOrNull(libraryId)?.let { "${it.name}($libraryId)" } ?: libraryId

  private fun BookMetadata.anyLock(): Boolean =
    titleLock || summaryLock || numberLock || numberSortLock || releaseDateLock ||
      authorsLock || tagsLock || isbnLock || linksLock || notableEntitiesLock
}
