// Runs the web app's own RibbitCrypto (extracted verbatim from index.html) under Node's WebCrypto.
import fs from 'fs';
globalThis.window = globalThis; window.localStorage = null;
eval(fs.readFileSync(new URL('./ribbitcrypto_web.js', import.meta.url), 'utf8'));
const [op, pw, header, text] = process.argv.slice(2);
if (op === 'enc') console.log(await RibbitCrypto.encrypt(text, pw, header));
else { try { console.log(await RibbitCrypto.decrypt(text, pw, header)); } catch (e) { console.log('DECRYPT_FAIL'); process.exit(1); } }
