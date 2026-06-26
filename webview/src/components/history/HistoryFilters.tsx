import { memo } from 'react';
import type { TFunction } from 'i18next';
import { SearchIcon } from '../Icons';

export interface HistoryFiltersProps {
  inputValue: string;
  onInputChange: (e: React.ChangeEvent<HTMLInputElement>) => void;
  t: TFunction;
}

export const HistoryFilters = memo(({ inputValue, onInputChange, t }: HistoryFiltersProps) => {
  return (
    <div className="history-search-container">
      <input
        type="text"
        className="history-search-input"
        placeholder={t('history.searchPlaceholder')}
        value={inputValue}
        onChange={onInputChange}
      />
      <SearchIcon size={16} className="history-search-icon" />
    </div>
  );
});

HistoryFilters.displayName = 'HistoryFilters';
