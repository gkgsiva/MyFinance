// Reads a PDF's text in reading order, line by line, the way the Android app's PDFBox stripper does with
// sortByPosition: words that share a line (even across table columns) are joined left to right.

/* eslint-disable @typescript-eslint/no-explicit-any */
export type PdfJs = {
  getDocument: (src: any) => { promise: Promise<any>; destroy?: () => Promise<void> };
};

export class PasswordNeeded extends Error {
  constructor(readonly wrong: boolean) {
    super(wrong ? 'Wrong password' : 'Password needed');
  }
}

/** Most fonts' cap height, as a share of the size: PDFBox uses it when half the bounding box is taller. */
const HEIGHT_CAP = 0.7;

interface Item { x: number; y: number; w: number; h: number; s: string; fs: number; dir: number }

/** PDFBox's overlap test: one baseline falls within the other's glyph height. */
function overlap(y1: number, h1: number, y2: number, h2: number): boolean {
  return Math.abs(y1 - y2) < 0.1 || (y2 <= y1 && y2 >= y1 - h1) || (y1 <= y2 && y1 >= y2 - h2);
}

/** PDFBox's TextPositionComparator: by x when the two overlap vertically, else top to bottom. */
function compare(a: Item, b: Item): number {
  // Only text in the same direction is compared: upright text first, rotated text (page margins) after.
  if (a.dir !== b.dir) return a.dir - b.dir;
  const aTop = a.y - a.h;
  const bTop = b.y - b.h;
  if (Math.abs(a.y - b.y) < 0.1 || (b.y >= aTop && b.y <= a.y) || (a.y >= bTop && a.y <= b.y)) return a.x - b.x;
  return a.y < b.y ? -1 : 1;
}

/** A stable merge sort of our own: the comparator is not transitive, so the result must not depend on the engine. */
function mergeSort(list: Item[]): Item[] {
  if (list.length <= 1) return list;
  // Insertion sort for short runs, as Java's sort does for small arrays.
  if (list.length < 32) {
    const a = list.slice();
    for (let i = 1; i < a.length; i++) {
      const v = a[i];
      let j = i - 1;
      while (j >= 0 && compare(a[j], v) > 0) { a[j + 1] = a[j]; j--; }
      a[j + 1] = v;
    }
    return a;
  }
  const mid = list.length >> 1;
  const l = mergeSort(list.slice(0, mid));
  const r = mergeSort(list.slice(mid));
  const out: Item[] = [];
  let i = 0;
  let j = 0;
  while (i < l.length && j < r.length) out.push(compare(l[i], r[j]) <= 0 ? l[i++] : r[j++]);
  while (i < l.length) out.push(l[i++]);
  while (j < r.length) out.push(r[j++]);
  return out;
}

/**
 * Lines of one page, as PDFBox's writePage builds them with sortByPosition: a new line when the next piece of
 * text doesn't overlap the line's lowest baseline, a word space when there is a gap, and the line's baseline
 * forgotten after a jump sideways (so a narration printed half a line lower starts its own line).
 */
export function linesOfPage(items: Item[]): string[] {
  const sorted = mergeSort(items.filter((i) => i.s.length > 0));
  const out: string[] = [];
  let line = '';
  let maxY = -Infinity;
  let maxH = -1;
  let prev: Item | null = null;
  for (const it of sorted) {
    if (prev) {
      if (!overlap(it.y, it.h, maxY, maxH)) {
        out.push(line.replace(/\s+$/, ''));
        line = '';
        maxY = -Infinity;
        maxH = -1;
      } else {
        const gap = it.x - (prev.x + prev.w);
        const space = it.fs * 0.25;
        if (gap > Math.min(space * 0.5, it.fs * 0.15) && !line.endsWith(' ') && !it.s.startsWith(' ')) line += ' ';
      }
      // PDFBox compares character starts: the last character of the previous piece, the first of this one.
      const lastCharX = prev.x + prev.w - prev.w / Math.max(1, prev.s.length);
      const space = it.fs * 0.25;
      if (Math.abs(it.x - lastCharX) > space * 1.5) {
        maxY = -Infinity;
        maxH = -1;
      }
    }
    if (it.y >= maxY) maxY = it.y;
    line += it.s;
    maxH = Math.max(maxH, it.h);
    prev = it;
  }
  if (line !== '') out.push(line.replace(/\s+$/, ''));
  return out;
}

/**
 * The text of a PDF, page by page. Throws [PasswordNeeded] when it is locked and [password] is missing or wrong.
 * [onPage] is told each page read, for a progress bar; [cancelled] stops between pages.
 */
export async function pdfText(
  pdfjs: PdfJs,
  bytes: Uint8Array,
  password: string | null,
  onPage: (page: number, pages: number) => void = () => {},
  cancelled: () => boolean = () => false,
): Promise<string> {
  const task = pdfjs.getDocument({
    data: bytes.slice(),
    password: password ?? undefined,
    isEvalSupported: false,
    disableFontFace: true,
    useSystemFonts: false,
    fontExtraProperties: true,
  });
  let doc: any;
  try {
    doc = await task.promise;
  } catch (e: any) {
    if (e && (e.name === 'PasswordException' || /password/i.test(String(e.message)))) {
      throw new PasswordNeeded(password != null && password !== '');
    }
    throw e;
  }
  try {
    const out: string[] = [];
    for (let p = 1; p <= doc.numPages; p++) {
      if (cancelled()) throw new Error('Stopped');
      const page = await doc.getPage(p);
      const view = page.getViewport({ scale: 1 });
      const tc = await page.getTextContent();
      // PDFBox's glyph height is half the font's bounding box (or its cap height when smaller): the fonts are only
      // handed over once the page's drawing is read.
      const fontHeight = new Map<string, number>();
      try {
        await page.getOperatorList();
        for (const name of Object.keys(tc.styles ?? {})) {
          try {
            const f = page.commonObjs.get(name);
            const bb = f?.bbox as number[] | undefined;
            const scale = f?.fontMatrix ? Math.abs(f.fontMatrix[3]) * 1000 : 1;
            if (bb && bb.length === 4) {
              const hh = (Math.abs(bb[3] - bb[1]) / 2 / 1000) * scale;
              if (hh > 0) fontHeight.set(name, Math.min(hh, HEIGHT_CAP));
            }
          } catch { /* not loaded */ }
        }
      } catch { /* drawing could not be read: default heights */ }
      const items: Item[] = [];
      for (const it of tc.items as any[]) {
        if (typeof it.str !== 'string' || it.str === '') continue;
        const t = it.transform as number[];
        // Viewport coordinates: y grows downward, as PDFBox's.
        let [x, y] = view.convertToViewportPoint(t[4], t[5]);
        const size = Math.hypot(t[2], t[3]) || Math.abs(t[3]) || it.height || 10;
        const angle = ((Math.round((Math.atan2(t[1], t[0]) * 180) / Math.PI / 90) * 90) % 360 + 360) % 360;
        if (angle !== 0) {
          // Rotated text: measured along its own direction, as PDFBox's direction-adjusted coordinates.
          const r = (angle * Math.PI) / 180;
          x = t[4] * Math.cos(r) + t[5] * Math.sin(r);
          y = -(-t[4] * Math.sin(r) + t[5] * Math.cos(r));
        }
        items.push({ x, y, w: it.width ?? 0, h: size * (fontHeight.get(it.fontName) ?? 0.66), s: it.str, fs: size, dir: angle });
      }
      out.push(linesOfPage(items).join('\n'));
      page.cleanup?.();
      onPage(p, doc.numPages);
    }
    return out.join('\n') + '\n';
  } finally {
    await doc.destroy?.();
  }
}
