import {LibraryDto} from '@/types/komga-libraries'
import {
  SearchConditionAllOfBook,
  SearchConditionAllOfSeries,
  SearchConditionBook,
  SearchConditionLibraryId,
  SearchConditionSeries,
  SearchOperatorIsNot,
} from '@/types/komga-search'

function hiddenIds(store: any): string[] {
  return store.getters.getLibrariesHidden.map((it: LibraryDto) => it.id)
}

// series search condition that also leaves out the hidden libraries
export function withoutHiddenSeries(store: any, condition?: SearchConditionSeries): SearchConditionSeries | undefined {
  const exclude = hiddenIds(store).map(id => new SearchConditionLibraryId(new SearchOperatorIsNot(id)))
  if (exclude.length === 0) return condition
  return new SearchConditionAllOfSeries(condition ? [condition, ...exclude] : exclude)
}

export function withoutHiddenBooks(store: any, condition?: SearchConditionBook): SearchConditionBook | undefined {
  const exclude = hiddenIds(store).map(id => new SearchConditionLibraryId(new SearchOperatorIsNot(id)))
  if (exclude.length === 0) return condition
  return new SearchConditionAllOfBook(condition ? [condition, ...exclude] : exclude)
}

// library filter for collections / read lists: undefined (no filter) when nothing is hidden
export function visibleLibraryIds(store: any): string[] | undefined {
  if (hiddenIds(store).length === 0) return undefined
  return store.getters.getLibrariesVisible.map((it: LibraryDto) => it.id)
}
