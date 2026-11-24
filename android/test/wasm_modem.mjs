// Drives the web app's compiled ribbit.wasm the same way index.html does.
//   node wasm_modem.mjs tx out.wav password text   (password "" = no encryption)
//   node wasm_modem.mjs rx in.wav  password
import fs from 'fs';
globalThis.window = globalThis; window.localStorage = null;
eval(fs.readFileSync(new URL('./ribbitcrypto_web.js', import.meta.url), 'utf8'));

const [mode, wavPath, password, text] = process.argv.slice(2);
let mem, ex, onFetch = null, signal = null;
const HEAP32 = () => new Int32Array(mem.buffer);
const ASM = { // addresses from ASM_CONSTS in index.html
	70060: () => {}, 70084: () => {}, 70108: () => {},
	70128: r => onFetch(r),
	70150: () => ex.readEncoder(),
	70167: n => { signal = new Float32Array(mem.buffer, ex.signal_pointer(), n).slice(); },
};
const imp = {
	emscripten_asm_const_int: (code, sig, argbuf) => ASM[code](HEAP32()[argbuf >> 2]),
	__assert_fail: () => { throw new Error('assert'); }, abort: () => { throw new Error('abort'); },
	emscripten_memcpy_js: (d, s, n) => { const u = new Uint8Array(mem.buffer); u.set(u.subarray(s, s + n), d); },
	emscripten_resize_heap: () => 0, fd_write: (fd, iov, cnt, pnum) => { // report everything as written, like index.html's printf hack
		const h = HEAP32(); let num = 0;
		for (let i = 0; i < cnt; i++) num += h[(iov >> 2) + 2 * i + 1];
		h[pnum >> 2] = num; return 0; },
	fd_close: () => 0, fd_seek: () => 0,
};
const { instance } = await WebAssembly.instantiate(fs.readFileSync(new URL('../../ribbit.wasm', import.meta.url)),
	{ env: imp, wasi_snapshot_preview1: imp });
ex = instance.exports; mem = ex.memory; ex.main();

const header = `Ana|LU2XYZ|GF11AA|null`;
if (mode === 'tx') {
	const body = password ? await RibbitCrypto.encrypt(text, password, header) : text;
	const enc = new TextEncoder().encode(`${header}&=${body}`);
	console.log('payload:', `${header}&=${body}`);
	const msg = new Uint8Array(mem.buffer, ex.message_pointer(), 256);
	for (let i = 0; i < 256; i++) msg[i] = enc[i];
	ex.initEncoder();
	// floatTo16BitPCM from index.html (clips like the browser output would)
	const pcm = Buffer.alloc(signal.length * 2);
	signal.forEach((v, i) => { const s = Math.max(-1, Math.min(1, v)); pcm.writeInt16LE(Math.round(s < 0 ? s * 0x8000 : s * 0x7FFF), i * 2); });
	const h = Buffer.alloc(44);
	h.write('RIFF', 0); h.writeUInt32LE(36 + pcm.length, 4); h.write('WAVEfmt ', 8); h.writeUInt32LE(16, 16);
	h.writeUInt16LE(1, 20); h.writeUInt16LE(1, 22); h.writeUInt32LE(8000, 24); h.writeUInt32LE(16000, 28);
	h.writeUInt16LE(2, 32); h.writeUInt16LE(16, 34); h.write('data', 36); h.writeUInt32LE(pcm.length, 40);
	fs.writeFileSync(wavPath, Buffer.concat([h, pcm]));
} else {
	const raw = fs.readFileSync(wavPath);
	const n = (raw.length - 44) / 2, FEED = ex.feed_length();
	const samples = new Float32Array(n + 8000 * 2); // plus 2 s of silence to flush
	for (let i = 0; i < n; i++) samples[i] = raw.readInt16LE(44 + 2 * i) / 32768;
	let got = 0;
	onFetch = async result => {
		got++;
		const str = new TextDecoder().decode(new Uint8Array(mem.buffer, ex.payload_pointer(), 256).slice());
		const sender = str.split('&=')[0], message = str.split('&=')[1];
		const body = message ? message.replace(/\0+$/, '') : '';
		console.log('frame result=' + result);
		console.log('sender :', sender);
		console.log('message:', body);
		if (RibbitCrypto.isEncrypted(body))
			console.log('decrypt:', await RibbitCrypto.decrypt(body, password, sender).catch(() => 'DECRYPT_FAIL'));
	};
	const feed = new Float32Array(mem.buffer, ex.feed_pointer(), FEED);
	for (let pos = 0; pos + FEED <= samples.length; pos += FEED) { feed.set(samples.subarray(pos, pos + FEED)); ex.digestFeed(); }
	await new Promise(r => setTimeout(r, 1500));
	if (!got) { console.log('NO FRAME'); process.exit(1); }
}
