import { createWriteStream } from 'node:fs';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import path from 'node:path';

const files = [
  ['https://services.gradle.org/distributions/gradle-8.10.2-bin.zip', '.tools/gradle.zip'],
  ['https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip', '.tools/cmdtools.zip'],
];

for (const [url, out] of files) {
  console.log('GET', url);
  const res = await fetch(url, { redirect: 'follow' });
  console.log(url, '->', res.status);
  if (!res.ok) throw new Error(`HTTP ${res.status} for ${url}`);
  const abs = path.resolve(out);
  await pipeline(Readable.fromWeb(/** @type {any} */ (res.body)), createWriteStream(abs));
  console.log('saved', abs);
}
console.log('DOWNLOADS-OK');
