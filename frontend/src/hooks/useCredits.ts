import { useQuery } from '@tanstack/react-query';
import { CREDITS_KEY, creditsApi } from '../api/credits';

/** The balance and the latest changes. Refreshed after approve / retry / cancel and on failures. */
export function useCredits() {
  return useQuery({ queryKey: CREDITS_KEY, queryFn: () => creditsApi.get(), staleTime: 10_000 });
}
