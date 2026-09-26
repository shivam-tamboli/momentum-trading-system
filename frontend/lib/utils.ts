import { clsx, type ClassValue } from "clsx"
import { twMerge } from "tailwind-merge"

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

// Alpaca/exchange-provided names carry a lot of boilerplate a ticker symbol already implies
// ("Moderna, Inc. Common Stock" — the "Inc." and "Common Stock" tell a reader nothing the MRNA
// badge next to it doesn't). Order matters: the more specific patterns ("Incorporated",
// "Corporation") run before the short, generic ones ("Inc", "Corp") so the short ones can't match
// a prefix of the long ones and leave a mangled remainder behind.
const NAME_SUFFIX_PATTERNS: RegExp[] = [
  /\bCommon Stock\b/gi,
  /\bClass [A-Z]\b/gi,
  /\bIncorporated\b/gi,
  /\bCorporation\b/gi,
  /\bCorp\.?(?![a-zA-Z])/gi,
  /\bInc\.?(?![a-zA-Z])/gi,
]

export function shortCompanyName(name: string): string {
  let result = name
  for (const pattern of NAME_SUFFIX_PATTERNS) {
    result = result.replace(pattern, " ")
  }
  return result.replace(/\s*,\s*/g, " ").replace(/\s+/g, " ").trim()
}
