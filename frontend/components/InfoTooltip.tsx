import { Info } from 'lucide-react';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';

interface InfoTooltipProps {
  message: string;
}

// A small (i) icon that explains one stat or chart in plain language on hover/focus — used
// wherever this page shows a number or chart whose exact meaning isn't obvious from its label
// alone (e.g. "is this a dollar amount or a percentage of what?").
export function InfoTooltip({ message }: InfoTooltipProps) {
  return (
    <Tooltip>
      <TooltipTrigger
        className="inline-flex h-4 w-4 shrink-0 items-center justify-center rounded-full text-muted-foreground/70 outline-none transition-colors hover:text-foreground focus-visible:text-foreground"
        aria-label="More information"
      >
        <Info className="h-3.5 w-3.5" />
      </TooltipTrigger>
      <TooltipContent>{message}</TooltipContent>
    </Tooltip>
  );
}
