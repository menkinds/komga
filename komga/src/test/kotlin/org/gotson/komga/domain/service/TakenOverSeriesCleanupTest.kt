package org.gotson.komga.domain.service

import com.ninjasquad.springmockk.MockkBean
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.application.tasks.TaskEmitter
import org.gotson.komga.domain.model.Book
import org.gotson.komga.domain.model.KomgaUser
import org.gotson.komga.domain.model.Library
import org.gotson.komga.domain.model.SearchContext
import org.gotson.komga.domain.model.Series
import org.gotson.komga.domain.model.SeriesCollection
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.model.makeLibrary
import org.gotson.komga.domain.model.makeSeries
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.KomgaUserRepository
import org.gotson.komga.domain.persistence.LibraryRepository
import org.gotson.komga.domain.persistence.ReadProgressRepository
import org.gotson.komga.domain.persistence.SeriesCollectionRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.gotson.komga.infrastructure.hash.Hasher
import org.gotson.komga.infrastructure.koreader.KoreaderHasher
import org.gotson.komga.toScanResult
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.net.URL
import java.nio.file.Path
import kotlin.io.path.nameWithoutExtension

@SpringBootTest
class TakenOverSeriesCleanupTest(
  @Autowired private val seriesRepository: SeriesRepository,
  @Autowired private val libraryRepository: LibraryRepository,
  @Autowired private val bookRepository: BookRepository,
  @Autowired private val libraryContentLifecycle: LibraryContentLifecycle,
  @Autowired private val seriesLifecycle: SeriesLifecycle,
  @Autowired private val libraryLifecycle: LibraryLifecycle,
  @Autowired private val collectionRepository: SeriesCollectionRepository,
  @Autowired private val collectionLifecycle: SeriesCollectionLifecycle,
  @Autowired private val readProgressRepository: ReadProgressRepository,
  @Autowired private val userRepository: KomgaUserRepository,
  @Autowired private val userLifecycle: KomgaUserLifecycle,
) {
  @MockkBean
  private lateinit var mockScanner: FileSystemScanner

  @MockkBean
  private lateinit var mockAnalyzer: BookAnalyzer

  @MockkBean
  private lateinit var mockHasher: Hasher

  @MockkBean
  private lateinit var mockKoHasher: KoreaderHasher

  @MockkBean
  private lateinit var mockTaskEmitter: TaskEmitter

  private val user = KomgaUser("takeover@example.org", "", id = "takeover-user")

  private val oldLibrary = makeLibrary(name = "comic_done", url = URL("file:/comic_done"))
  private val newLibrary = makeLibrary(name = "comic", url = URL("file:/comic"))

  private val oldName = "[あらた伊里] グッバイ宣言 第01~03巻 (完)"
  private val newName = "[あらた伊里] グッバイ宣言 第01~03巻"
  private val volumes = listOf("v01", "v02", "v03")

  @BeforeAll
  fun setup() {
    userRepository.insert(user)
  }

  @AfterAll
  fun tearDown() {
    userRepository.findAll().forEach { userLifecycle.deleteUser(it) }
  }

  @BeforeEach
  fun beforeEach() {
    every { mockTaskEmitter.refreshBookMetadata(any<Book>(), any()) } just Runs
    every { mockTaskEmitter.refreshSeriesMetadata(any<String>(), any()) } just Runs
    val captured = slot<Path>()
    every { mockHasher.computeHash(capture(captured)) } answers { "HASH-${captured.captured.nameWithoutExtension}" }
  }

  @AfterEach
  fun clear() {
    libraryRepository.findAll().forEach { libraryLifecycle.deleteLibrary(it) }
  }

  private fun series(
    name: String,
    folder: String,
  ): Series = makeSeries(name = name, url = URL("file:$folder"))

  private fun books(
    folder: String,
    names: List<String> = volumes,
  ): List<Book> =
    names.map { name ->
      makeBook(name, url = URL("file:$folder/$name.cbz")).copy(fileSize = 1000L + name.hashCode())
    }

  private fun scanResults(vararg scans: Map<Series, List<Book>>) {
    every { mockScanner.scanRootFolder(any()) }.returnsMany(scans.map { it.toScanResult() })
  }

  /** First scan of the old library, then hash the books and read them, as analysis and a user would. */
  private fun readOldSeries(library: Library = oldLibrary): Series {
    libraryContentLifecycle.scanRootFolder(library)
    val old = seriesRepository.findAllByLibraryId(library.id).single()
    bookRepository.findAllBySeriesId(old.id).forEach { bookRepository.update(it.copy(fileHash = "HASH-${it.name}")) }
    seriesLifecycle.markReadProgressCompleted(old.id, user)
    return old
  }

  private fun liveBooks(library: Library): List<Book> = bookRepository.findAll().filter { it.libraryId == library.id && it.deletedDate == null }

  private fun assertProgressOn(books: Collection<Book>) {
    assertThat(books).isNotEmpty
    books.forEach { book ->
      assertThat(readProgressRepository.findByBookIdAndUserIdOrNull(book.id, user.id)?.completed)
        .`as`("read progress on ${book.name}")
        .isTrue()
    }
  }

  @Test
  fun `given series moved to another library when the new library is scanned first then the old series is deleted`() {
    libraryRepository.insert(oldLibrary)
    libraryRepository.insert(newLibrary)
    scanResults(
      mapOf(series(oldName, "/comic_done/goodbye-done") to books("/comic_done/goodbye-done")),
      mapOf(series(newName, "/comic/goodbye") to books("/comic/goodbye")),
      emptyMap(),
    )
    val old = readOldSeries()
    collectionLifecycle.addCollection(SeriesCollection("favorites", seriesIds = listOf(old.id)))

    libraryContentLifecycle.scanRootFolder(newLibrary)
    libraryContentLifecycle.scanRootFolder(oldLibrary)

    assertThat(seriesRepository.findAllByLibraryId(oldLibrary.id)).isEmpty()
    assertThat(bookRepository.findAllBySeriesId(old.id)).isEmpty()
    val newSeries = seriesRepository.findAllByLibraryId(newLibrary.id).single()
    assertThat(newSeries.deletedDate).isNull()
    assertThat(liveBooks(newLibrary)).hasSize(3)
    assertProgressOn(liveBooks(newLibrary))
    assertThat(collectionRepository.findAllContainingSeriesId(newSeries.id, SearchContext.empty()).map { it.name })
      .containsExactly("favorites")
  }

  @Test
  fun `given series moved to another library when the old library is scanned first then the old series is deleted after the second scan`() {
    libraryRepository.insert(oldLibrary)
    libraryRepository.insert(newLibrary)
    scanResults(
      mapOf(series(oldName, "/comic_done/goodbye-done") to books("/comic_done/goodbye-done")),
      emptyMap(),
      mapOf(series(newName, "/comic/goodbye") to books("/comic/goodbye")),
    )
    val old = readOldSeries()

    libraryContentLifecycle.scanRootFolder(oldLibrary)

    val trashed = seriesRepository.findAllByLibraryId(oldLibrary.id)
    assertThat(trashed).hasSize(1)
    assertThat(trashed.single().id).isEqualTo(old.id)
    assertThat(trashed.single().deletedDate).isNotNull()

    libraryContentLifecycle.scanRootFolder(newLibrary)

    assertThat(seriesRepository.findAllByLibraryId(oldLibrary.id)).isEmpty()
    assertThat(liveBooks(newLibrary)).hasSize(3)
    assertProgressOn(liveBooks(newLibrary))
  }

  @Test
  fun `given series moved to another library with an added volume when the old library is scanned first then the old series is deleted after the second scan`() {
    libraryRepository.insert(oldLibrary)
    libraryRepository.insert(newLibrary)
    scanResults(
      mapOf(series(oldName, "/comic_done/goodbye-done") to books("/comic_done/goodbye-done")),
      emptyMap(),
      mapOf(series(newName, "/comic/goodbye") to books("/comic/goodbye", volumes + "v04")),
    )
    val old = readOldSeries()

    libraryContentLifecycle.scanRootFolder(oldLibrary)
    assertThat(seriesRepository.findAllByLibraryId(oldLibrary.id).single().deletedDate).isNotNull()

    libraryContentLifecycle.scanRootFolder(newLibrary)

    assertThat(seriesRepository.findAllByLibraryId(oldLibrary.id)).isEmpty()
    assertThat(seriesRepository.findByIdOrNull(old.id)).isNull()
    assertThat(liveBooks(newLibrary)).hasSize(4)
    assertProgressOn(liveBooks(newLibrary).filter { it.name in volumes })
  }

  @Test
  fun `given series renamed in the same library then no old series is left and progress is kept`() {
    libraryRepository.insert(oldLibrary)
    scanResults(
      mapOf(series(oldName, "/comic_done/goodbye-done") to books("/comic_done/goodbye-done")),
      mapOf(series(newName, "/comic_done/goodbye") to books("/comic_done/goodbye")),
    )
    readOldSeries()

    libraryContentLifecycle.scanRootFolder(oldLibrary)

    val all = seriesRepository.findAllByLibraryId(oldLibrary.id)
    assertThat(all).hasSize(1)
    assertThat(all.single().deletedDate).isNull()
    assertThat(all.single().name).isEqualTo(newName)
    assertThat(bookRepository.findAll().filter { it.deletedDate != null }).isEmpty()
    assertThat(liveBooks(oldLibrary)).hasSize(3)
    assertProgressOn(liveBooks(oldLibrary))
  }

  @Test
  fun `given series moved to another library with one volume missing then the old series stays in the trash`() {
    libraryRepository.insert(oldLibrary)
    libraryRepository.insert(newLibrary)
    scanResults(
      mapOf(series(oldName, "/comic_done/goodbye-done") to books("/comic_done/goodbye-done")),
      mapOf(series(newName, "/comic/goodbye") to books("/comic/goodbye", listOf("v01", "v02"))),
      emptyMap(),
    )
    val old = readOldSeries()

    libraryContentLifecycle.scanRootFolder(newLibrary)
    libraryContentLifecycle.scanRootFolder(oldLibrary)

    val trashed = seriesRepository.findAllByLibraryId(oldLibrary.id)
    assertThat(trashed).hasSize(1)
    assertThat(trashed.single().id).isEqualTo(old.id)
    assertThat(trashed.single().deletedDate).isNotNull()
    assertThat(bookRepository.findAllBySeriesId(old.id)).hasSize(3)
    assertThat(liveBooks(newLibrary)).hasSize(2)
    assertProgressOn(liveBooks(newLibrary))
  }

  @Test
  fun `given series deleted from disk with no copy anywhere then it stays in the trash`() {
    libraryRepository.insert(oldLibrary)
    libraryRepository.insert(newLibrary)
    scanResults(
      mapOf(series(oldName, "/comic_done/goodbye-done") to books("/comic_done/goodbye-done")),
      emptyMap(),
      mapOf(series("other", "/comic/other") to books("/comic/other", listOf("x01"))),
    )
    val old = readOldSeries()

    libraryContentLifecycle.scanRootFolder(oldLibrary)
    libraryContentLifecycle.scanRootFolder(newLibrary)

    val trashed = seriesRepository.findAllByLibraryId(oldLibrary.id)
    assertThat(trashed).hasSize(1)
    assertThat(trashed.single().id).isEqualTo(old.id)
    assertThat(trashed.single().deletedDate).isNotNull()
    assertThat(bookRepository.findAllBySeriesId(old.id)).hasSize(3)
    assertProgressOn(bookRepository.findAllBySeriesId(old.id))
    assertThat(readProgressRepository.findAllByBookIdsAndUserId(liveBooks(newLibrary).map { it.id }, user.id)).isEmpty()
  }
}
