import { createWriteStream } from 'node:fs';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';

const base = 'https://dl.google.com/android/repository/';
const zips = ['platform-tools_r37.0.2-win.zip', 'platform-35_r02.zip', 'build-tools_r35_windows.zip'];

for (const z of zips) {
  console.log('GET', z);
  const res = await fetch(base + z, { redirect: 'follow' });
  if (!res.ok) throw new Error(`HTTP ${res.status} for ${z}`);
  await pipeline(Readable.fromWeb(/** @type {any} */ (res.body)), createWriteStream(`.tools/sdkpkg-${z}`));
  console.log('saved', z);
}
console.log('SDK-ZIPS-OK');
