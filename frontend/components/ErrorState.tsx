import { AlertTriangle } from 'lucide-react';

interface ErrorStateProps {
  message?: string;
  onRetry?: () => void;
}

export function ErrorState({ message = "Couldn't load this data.", onRetry }: ErrorStateProps) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 py-8 text-center">
      <AlertTriangle className="h-8 w-8 text-destructive/60" />
      <p className="text-sm text-muted-foreground">{message}</p>
      {onRetry && (
        <button
          type="button"
          onClick={onRetry}
          className="text-sm font-medium text-primary hover:underline"
        >
          Try again
        </button>
      )}
    </div>
  );
}
