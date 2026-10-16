package org.gotson.komga.domain.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.Author
import org.gotson.komga.domain.model.Book
import org.gotson.komga.domain.model.ReadList
import org.gotson.komga.domain.model.ReadProgress
import org.gotson.komga.domain.model.SearchContext
import org.gotson.komga.domain.model.Series
import org.gotson.komga.domain.model.SeriesCollection
import org.gotson.komga.domain.model.SeriesMetadata
import org.gotson.komga.domain.persistence.BookMetadataRepository
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.ReadListRepository
import org.gotson.komga.domain.persistence.ReadProgressRepository
import org.gotson.komga.domain.persistence.SeriesCollectionRepository
import org.gotson.komga.domain.persistence.SeriesMetadataRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate
import java.time.LocalDateTime

private val logger = KotlinLogging.logger {}

/**
 * Fork-only snapshot of read progress and metadata, keyed by file hash.
 * Upstream trash rows are removed by empty-trash; this table is not.
 */
@Component
class HashArchive(
  @Qualifier("dslContextRW") private val dsl: DSLContext,
  private val objectMapper: ObjectMapper,
  private val transactionTemplate: TransactionTemplate,
  private val bookRepository: BookRepository,
  private val bookMetadataRepository: BookMetadataRepository,
  private val seriesRepository: SeriesRepository,
  private val seriesMetadataRepository: SeriesMetadataRepository,
  private val readProgressRepository: ReadProgressRepository,
  private val collectionRepository: SeriesCollectionRepository,
  private val readListRepository: ReadListRepository,
) {
  private val table = DSL.table(DSL.name("HASH_ARCHIVE"))
  private val fileHashField = DSL.field(DSL.name("FILE_HASH"), SQLDataType.VARCHAR)
  private val libraryIdField = DSL.field(DSL.name("LIBRARY_ID"), SQLDataType.VARCHAR)
  private val seriesIdField = DSL.field(DSL.name("SERIES_ID"), SQLDataType.VARCHAR)
  private val payloadField = DSL.field(DSL.name("PAYLOAD"), SQLDataType.VARCHAR)
  private val updatedField = DSL.field(DSL.name("UPDATED_DATE"), SQLDataType.VARCHAR)
  private val bookTable = DSL.table(DSL.name("BOOK"))
  private val bookIdField = DSL.field(DSL.name("ID"), SQLDataType.VARCHAR)
  private val bookHashField = DSL.field(DSL.name("FILE_HASH"), SQLDataType.VARCHAR)
  private val bookDeletedField = DSL.field(DSL.name("DELETED_DATE"), SQLDataType.LOCALDATETIME)

  fun archiveAll(books: Collection<Book>) {
    books.forEach { archive(it) }
  }

  fun archive(book: Book) {
    if (book.fileHash.isBlank()) return
    val snapshot = snapshot(book) ?: return
    write(book.fileHash, book.libraryId, snapshot.seriesId, snapshot)
  }

  /**
   * Copy archived progress and metadata onto [book] when it is the only live book with that hash.
   */
  fun restore(book: Book) {
    if (book.fileHash.isBlank() || book.deletedDate != null) return
    if (otherLiveBookHasHash(book)) return
    val snapshot = read(book.fileHash) ?: return
    logger.info { "Restoring hash archive onto book ${book.id} (${book.fileHash})" }
    applyBook(book, snapshot)
    applyReadProgress(book, snapshot)
    applyReadLists(book, snapshot)
    applySeriesIfMajority(book, snapshot)
  }

  private fun snapshot(book: Book): Snapshot? {
    val metadata = bookMetadataRepository.findByIdOrNull(book.id) ?: return null
    val series = seriesRepository.findByIdOrNull(book.seriesId)
    val seriesMetadata = series?.let { seriesMetadataRepository.findByIdOrNull(it.id) }
    val progress =
      readProgressRepository.findAllByBookId(book.id).map {
        ArchivedProgress(
          userId = it.userId,
          page = it.page,
          completed = it.completed,
          readDate = it.readDate.toString(),
          createdDate = it.createdDate.toString(),
          lastModifiedDate = it.lastModifiedDate.toString(),
          deviceId = it.deviceId,
          deviceName = it.deviceName,
        )
      }
    val collections =
      series?.let {
        collectionRepository.findAllContainingSeriesId(it.id, SearchContext.empty()).map { col ->
          ArchivedRef(col.id, col.name)
        }
      } ?: emptyList()
    val readLists =
      readListRepository.findAllContainingBookId(book.id, SearchContext.empty()).map { list ->
        val index =
          list.bookIds.entries
            .firstOrNull { it.value == book.id }
            ?.key ?: 0
        ArchivedReadListRef(list.id, list.name, index)
      }
    return Snapshot(
      seriesId = book.seriesId,
      seriesName = series?.name ?: "",
      bookMetadata =
        ArchivedBookMetadata(
          title = metadata.title,
          summary = metadata.summary,
          number = metadata.number,
          numberSort = metadata.numberSort,
          releaseDate = metadata.releaseDate?.toString(),
          authors = metadata.authors.map { ArchivedAuthor(it.name, it.role) },
          tags = metadata.tags,
          isbn = metadata.isbn,
          titleLock = metadata.titleLock,
          summaryLock = metadata.summaryLock,
          numberLock = metadata.numberLock,
          numberSortLock = metadata.numberSortLock,
          releaseDateLock = metadata.releaseDateLock,
          authorsLock = metadata.authorsLock,
          tagsLock = metadata.tagsLock,
          isbnLock = metadata.isbnLock,
        ),
      seriesMetadata =
        seriesMetadata?.let {
          ArchivedSeriesMetadata(
            status = it.status.name,
            title = it.title,
            titleSort = it.titleSort,
            summary = it.summary,
            readingDirection = it.readingDirection?.name,
            publisher = it.publisher,
            ageRating = it.ageRating,
            language = it.language,
            genres = it.genres,
            tags = it.tags,
            statusLock = it.statusLock,
            titleLock = it.titleLock,
            titleSortLock = it.titleSortLock,
            summaryLock = it.summaryLock,
            readingDirectionLock = it.readingDirectionLock,
            publisherLock = it.publisherLock,
            ageRatingLock = it.ageRatingLock,
            languageLock = it.languageLock,
            genresLock = it.genresLock,
            tagsLock = it.tagsLock,
          )
        },
      readProgress = progress,
      collections = collections,
      readLists = readLists,
    )
  }

  private fun applyBook(
    book: Book,
    snapshot: Snapshot,
  ) {
    val current = bookMetadataRepository.findByIdOrNull(book.id) ?: return
    val archived = snapshot.bookMetadata
    bookMetadataRepository.update(
      current.copy(
        title = if (archived.titleLock) archived.title else current.title,
        summary = archived.summary,
        number = if (archived.numberLock) archived.number else current.number,
        numberSort = if (archived.numberSortLock) archived.numberSort else current.numberSort,
        releaseDate = archived.releaseDate?.let { LocalDate.parse(it) },
        authors = archived.authors.map { Author(it.name, it.role) },
        tags = archived.tags,
        isbn = archived.isbn,
        titleLock = archived.titleLock,
        summaryLock = archived.summaryLock,
        numberLock = archived.numberLock,
        numberSortLock = archived.numberSortLock,
        releaseDateLock = archived.releaseDateLock,
        authorsLock = archived.authorsLock,
        tagsLock = archived.tagsLock,
        isbnLock = archived.isbnLock,
      ),
    )
  }

  private fun applyReadProgress(
    book: Book,
    snapshot: Snapshot,
  ) {
    snapshot.readProgress.forEach { archived ->
      if (readProgressRepository.findByBookIdAndUserIdOrNull(book.id, archived.userId) != null) return@forEach
      readProgressRepository.save(
        ReadProgress(
          bookId = book.id,
          userId = archived.userId,
          page = archived.page,
          completed = archived.completed,
          readDate = LocalDateTime.parse(archived.readDate),
          deviceId = archived.deviceId,
          deviceName = archived.deviceName,
          createdDate = LocalDateTime.parse(archived.createdDate),
          lastModifiedDate = LocalDateTime.parse(archived.lastModifiedDate),
        ),
      )
    }
  }

  private fun applyReadLists(
    book: Book,
    snapshot: Snapshot,
  ) {
    snapshot.readLists.forEach { ref ->
      val existing = readListRepository.findByIdOrNull(ref.id, SearchContext.empty())
      if (existing == null) {
        readListRepository.insert(
          ReadList(
            name = ref.name,
            bookIds = sortedMapOf(ref.index to book.id),
            id = ref.id,
          ),
        )
        return@forEach
      }
      if (existing.bookIds.containsValue(book.id)) return@forEach
      val ids = existing.bookIds.toMutableMap()
      val index = if (ids.containsKey(ref.index)) (ids.keys.maxOrNull() ?: 0) + 1 else ref.index
      ids[index] = book.id
      readListRepository.update(existing.copy(bookIds = ids.toSortedMap()))
    }
  }

  private fun applySeriesIfMajority(
    book: Book,
    snapshot: Snapshot,
  ) {
    val seriesMeta = snapshot.seriesMetadata ?: return
    val series = seriesRepository.findByIdOrNull(book.seriesId) ?: return
    if (series.deletedDate != null) return
    val books = bookRepository.findAllBySeriesId(series.id).filter { it.deletedDate == null }
    if (books.isEmpty()) return
    val archivedSeriesIds =
      books.mapNotNull { candidate ->
        if (candidate.fileHash.isBlank()) null else read(candidate.fileHash)?.seriesId
      }
    val votes = archivedSeriesIds.count { it == snapshot.seriesId }
    if (votes * 2 <= books.size) return
    applySeriesMetadata(series, seriesMeta)
    restoreCollections(series, snapshot.collections)
  }

  private fun applySeriesMetadata(
    series: Series,
    archived: ArchivedSeriesMetadata,
  ) {
    val current = seriesMetadataRepository.findByIdOrNull(series.id) ?: return
    val status = runCatching { SeriesMetadata.Status.valueOf(archived.status) }.getOrDefault(current.status)
    val direction =
      archived.readingDirection?.let { name ->
        runCatching { SeriesMetadata.ReadingDirection.valueOf(name) }.getOrNull()
      }
    seriesMetadataRepository.update(
      current.copy(
        status = status,
        title = if (archived.titleLock) archived.title else current.title,
        titleSort = if (archived.titleSortLock) archived.titleSort else current.titleSort,
        summary = archived.summary,
        readingDirection = direction ?: current.readingDirection,
        publisher = archived.publisher,
        ageRating = archived.ageRating,
        language = archived.language,
        genres = archived.genres,
        tags = archived.tags,
        statusLock = archived.statusLock,
        titleLock = archived.titleLock,
        titleSortLock = archived.titleSortLock,
        summaryLock = archived.summaryLock,
        readingDirectionLock = archived.readingDirectionLock,
        publisherLock = archived.publisherLock,
        ageRatingLock = archived.ageRatingLock,
        languageLock = archived.languageLock,
        genresLock = archived.genresLock,
        tagsLock = archived.tagsLock,
      ),
    )
  }

  private fun restoreCollections(
    series: Series,
    collections: List<ArchivedRef>,
  ) {
    collections.forEach { ref ->
      val existing = collectionRepository.findByIdOrNull(ref.id, SearchContext.empty())
      if (existing == null) {
        collectionRepository.insert(SeriesCollection(name = ref.name, seriesIds = listOf(series.id), id = ref.id))
      } else if (series.id !in existing.seriesIds) {
        collectionRepository.update(existing.copy(seriesIds = existing.seriesIds + series.id))
      }
    }
  }

  private fun otherLiveBookHasHash(book: Book): Boolean =
    dsl.fetchExists(
      dsl
        .selectOne()
        .from(bookTable)
        .where(bookHashField.eq(book.fileHash))
        .and(bookDeletedField.isNull)
        .and(bookIdField.ne(book.id)),
    )

  private fun write(
    hash: String,
    libraryId: String,
    seriesId: String,
    snapshot: Snapshot,
  ) {
    val json = objectMapper.writeValueAsString(snapshot)
    val now = LocalDateTime.now().toString()
    transactionTemplate.executeWithoutResult {
      dsl.deleteFrom(table).where(fileHashField.eq(hash)).execute()
      dsl
        .insertInto(table)
        .set(fileHashField, hash)
        .set(libraryIdField, libraryId)
        .set(seriesIdField, seriesId)
        .set(payloadField, json)
        .set(updatedField, now)
        .execute()
    }
  }

  private fun read(hash: String): Snapshot? {
    val json =
      dsl
        .select(payloadField)
        .from(table)
        .where(fileHashField.eq(hash))
        .fetchOne(payloadField)
        ?: return null
    return runCatching { objectMapper.readValue<Snapshot>(json) }
      .onFailure { logger.warn(it) { "Ignoring unreadable hash archive for $hash" } }
      .getOrNull()
  }

  data class Snapshot(
    val seriesId: String = "",
    val seriesName: String = "",
    val bookMetadata: ArchivedBookMetadata = ArchivedBookMetadata(),
    val seriesMetadata: ArchivedSeriesMetadata? = null,
    val readProgress: List<ArchivedProgress> = emptyList(),
    val collections: List<ArchivedRef> = emptyList(),
    val readLists: List<ArchivedReadListRef> = emptyList(),
  )

  data class ArchivedBookMetadata(
    val title: String = "",
    val summary: String = "",
    val number: String = "",
    val numberSort: Float = 0f,
    val releaseDate: String? = null,
    val authors: List<ArchivedAuthor> = emptyList(),
    val tags: Set<String> = emptySet(),
    val isbn: String = "",
    val titleLock: Boolean = false,
    val summaryLock: Boolean = false,
    val numberLock: Boolean = false,
    val numberSortLock: Boolean = false,
    val releaseDateLock: Boolean = false,
    val authorsLock: Boolean = false,
    val tagsLock: Boolean = false,
    val isbnLock: Boolean = false,
  )

  data class ArchivedAuthor(
    val name: String = "",
    val role: String = "",
  )

  data class ArchivedSeriesMetadata(
    val status: String = SeriesMetadata.Status.ONGOING.name,
    val title: String = "",
    val titleSort: String = "",
    val summary: String = "",
    val readingDirection: String? = null,
    val publisher: String = "",
    val ageRating: Int? = null,
    val language: String = "",
    val genres: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val statusLock: Boolean = false,
    val titleLock: Boolean = false,
    val titleSortLock: Boolean = false,
    val summaryLock: Boolean = false,
    val readingDirectionLock: Boolean = false,
    val publisherLock: Boolean = false,
    val ageRatingLock: Boolean = false,
    val languageLock: Boolean = false,
    val genresLock: Boolean = false,
    val tagsLock: Boolean = false,
  )

  data class ArchivedProgress(
    val userId: String = "",
    val page: Int = 0,
    val completed: Boolean = false,
    val readDate: String = "",
    val createdDate: String = "",
    val lastModifiedDate: String = "",
    val deviceId: String = "",
    val deviceName: String = "",
  )

  data class ArchivedRef(
    val id: String = "",
    val name: String = "",
  )

  data class ArchivedReadListRef(
    val id: String = "",
    val name: String = "",
    val index: Int = 0,
  )
}
