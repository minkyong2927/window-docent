// QR 코드 그리기 (여행 기록 · 외국어 승객이 내릴 때 명소 목록). 바깥 묶음 없이 직접 씀.
// 바이트 방식 · 오류 정정 L · 버전 1 ~ 6 (UTF-8 134바이트까지) · 마스크 0. 규칙은 ISO/IEC 18004 그대로.
(function () {
  const TOTAL = [0, 26, 44, 70, 100, 134, 172];      // 버전별 전체 코드워드
  const ECC = [0, 7, 10, 15, 20, 26, 18];            // L 블록마다 오류 정정 코드워드
  const BLOCKS = [0, 1, 1, 1, 1, 1, 2];              // L 블록 수

  function mul(x, y) {                                // GF(256) 곱 (원시 다항식 0x11D)
    let z = 0;
    for (let i = 7; i >= 0; i--) { z = (z << 1) ^ ((z >>> 7) * 0x11d); z ^= ((y >>> i) & 1) * x; }
    return z & 0xff;
  }
  function divisor(degree) {
    const r = new Array(degree).fill(0); r[degree - 1] = 1; let root = 1;
    for (let i = 0; i < degree; i++) {
      for (let j = 0; j < degree; j++) { r[j] = mul(r[j], root); if (j + 1 < degree) r[j] ^= r[j + 1]; }
      root = mul(root, 2);
    }
    return r;
  }
  function remainder(data, div) {
    const r = new Array(div.length).fill(0);
    for (const b of data) { const f = b ^ r.shift(); r.push(0); div.forEach((c, i) => { r[i] ^= mul(c, f); }); }
    return r;
  }
  function utf8(text) { return Array.from(new TextEncoder().encode(text)); }

  /** 글 → 코드워드(오류 정정 붙여 섞은 것) · 들어갈 버전이 없으면 null */
  function codewords(text) {
    const bytes = utf8(text);
    let v = 1;
    while (v <= 6 && 4 + 8 + bytes.length * 8 > (TOTAL[v] - ECC[v] * BLOCKS[v]) * 8) v++;
    if (v > 6) return null;
    const cap = TOTAL[v] - ECC[v] * BLOCKS[v];
    const bits = [];
    const put = (val, n) => { for (let i = n - 1; i >= 0; i--) bits.push((val >>> i) & 1); };
    put(4, 4); put(bytes.length, 8); bytes.forEach(b => put(b, 8));
    put(0, Math.min(4, cap * 8 - bits.length));
    while (bits.length % 8) bits.push(0);
    const data = [];
    for (let i = 0; i < bits.length; i += 8) data.push(bits.slice(i, i + 8).reduce((a, b) => (a << 1) | b, 0));
    for (let pad = 0xec; data.length < cap; pad ^= 0xec ^ 0x11) data.push(pad);
    // 블록 나누기 · 오류 정정 · 섞기
    const nb = BLOCKS[v], ecl = ECC[v], raw = TOTAL[v];
    const nShort = nb - raw % nb, shortLen = Math.floor(raw / nb), div = divisor(ecl);
    const blocks = [];
    for (let i = 0, k = 0; i < nb; i++) {
      const dat = data.slice(k, k + shortLen - ecl + (i < nShort ? 0 : 1)); k += dat.length;
      const e = remainder(dat, div);
      if (i < nShort) dat.push(0);
      blocks.push(dat.concat(e));
    }
    const out = [];
    for (let i = 0; i < blocks[0].length; i++) blocks.forEach((b, j) => { if (i !== shortLen - ecl || j >= nShort) out.push(b[i]); });
    return { version: v, data, codewords: out, blocks: nb, ecc: ecl };
  }

  /** 글 → 모듈 판 {size, version, m[y][x] true=검정} */
  function matrix(text) {
    const cw = codewords(text);
    if (!cw) return null;
    const v = cw.version, size = 17 + 4 * v;
    const m = Array.from({ length: size }, () => new Array(size).fill(false));
    const fn = Array.from({ length: size }, () => new Array(size).fill(false));
    const set = (x, y, dark) => { m[y][x] = dark; fn[y][x] = true; };
    for (let i = 0; i < size; i++) { set(6, i, i % 2 === 0); set(i, 6, i % 2 === 0); }
    const finder = (cx, cy) => {
      for (let dy = -4; dy <= 4; dy++) for (let dx = -4; dx <= 4; dx++) {
        const x = cx + dx, y = cy + dy, d = Math.max(Math.abs(dx), Math.abs(dy));
        if (x >= 0 && x < size && y >= 0 && y < size) set(x, y, d !== 2 && d !== 4);
      }
    };
    finder(3, 3); finder(size - 4, 3); finder(3, size - 4);
    if (v >= 2) {
      const pos = [6, size - 7];
      for (let i = 0; i < 2; i++) for (let j = 0; j < 2; j++) {
        if ((i === 0 && j === 0) || (i === 0 && j === 1) || (i === 1 && j === 0)) continue;
        for (let dy = -2; dy <= 2; dy++) for (let dx = -2; dx <= 2; dx++) set(pos[i] + dx, pos[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
      }
    }
    // 형식 정보 자리를 먼저 잡음 (값은 아래에서)
    const format = mask => {
      const d = (1 << 3) | mask;                       // L = 01
      let rem = d;
      for (let i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
      const bits = ((d << 10) | rem) ^ 0x5412;
      const b = i => ((bits >>> i) & 1) === 1;
      for (let i = 0; i <= 5; i++) set(8, i, b(i));
      set(8, 7, b(6)); set(8, 8, b(7)); set(7, 8, b(8));
      for (let i = 9; i < 15; i++) set(14 - i, 8, b(i));
      for (let i = 0; i < 8; i++) set(size - 1 - i, 8, b(i));
      for (let i = 8; i < 15; i++) set(8, size - 15 + i, b(i));
      set(8, size - 8, true);                            // 늘 검정 한 칸
      return bits;
    };
    format(0);
    // 데이터 놓기 (오른쪽 아래부터 두 줄씩 지그재그)
    let i = 0;
    const all = cw.codewords;
    for (let right = size - 1; right >= 1; right -= 2) {
      if (right === 6) right = 5;
      for (let vert = 0; vert < size; vert++) for (let j = 0; j < 2; j++) {
        const x = right - j, up = ((right + 1) & 2) === 0, y = up ? size - 1 - vert : vert;
        if (!fn[y][x] && i < all.length * 8) { m[y][x] = ((all[i >>> 3] >>> (7 - (i & 7))) & 1) === 1; i++; }
      }
    }
    // 마스크 0: (x + y) 가 짝수인 데이터 칸을 뒤집음
    for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) if (!fn[y][x] && (x + y) % 2 === 0) m[y][x] = !m[y][x];
    return { size, version: v, m, fn, codewords: all, data: cw.data, formatBits: format(0) };
  }

  /** 글 → SVG (조용한 테두리 4칸 포함) · 너무 길면 빈 글 */
  function svg(text, px) {
    const q = matrix(text);
    if (!q) return '';
    const n = q.size + 8;
    let path = '';
    for (let y = 0; y < q.size; y++) for (let x = 0; x < q.size; x++) if (q.m[y][x]) path += 'M' + (x + 4) + ',' + (y + 4) + 'h1v1h-1z';
    return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ' + n + ' ' + n + '" width="' + px + '" height="' + px + '" shape-rendering="crispEdges">'
      + '<rect width="' + n + '" height="' + n + '" fill="#fff"/><path d="' + path + '" fill="#000"/></svg>';
  }

  const api = { matrix, svg, codewords, remainder, divisor };
  if (typeof window !== 'undefined') window.QR = api;
  if (typeof module !== 'undefined') module.exports = api;
})();
