/* =====================================================================
   cartoon-text.js — titre cartoon 3D multilingue, rendu en SVG vectoriel
   API :
     CartoonText.render(host, texte, options?)   → Promise
     CartoonText.update(host, texte)             → change seulement le texte
   Le rendu est 100 % vectoriel (net à toute taille), s'adapte à la largeur
   du conteneur et passe sur 2 lignes si le texte devient trop petit.

   Fourni par l'utilisateur (26/09/2026) pour l'animation "Mort du joueur"
   (voir player-view.js/player.css, .death-anim-*) - choisi PLUTÔT que le
   texte à gradient orange/cyan codé en dur du mockup HTML fourni en même
   temps (voir docs/03-architecture-technique.md, entrée du 26/09/2026) :
   seul cet outil vectoriel s'adapte automatiquement à une traduction plus
   longue ou plus courte que le français ("il faut que les textes soient
   faits en code", exigence explicite du multilingue de ce projet) - une
   décision confirmée avec l'utilisateur avant implémentation. Réutilisable
   pour tout futur titre cartoon multilingue (l'exemple fourni couvrait
   aussi un écran "En prison").
   ===================================================================== */
(function (global) {
  'use strict';

  // Couleurs relevées sur l'illustration (haut de face, bas de face, tranche)
  const PALETTE = [
    { top: '#f7784f', bot: '#df2a28', side: '#8e1c22' }, // rouge-orangé
    { top: '#ffb444', bot: '#f07418', side: '#a94a14' }, // orange
    { top: '#f6848f', bot: '#d02a5d', side: '#861843' }, // rose
    { top: '#ffd24f', bot: '#f59a22', side: '#b0600f' }, // ambre
    { top: '#5bb8f0', bot: '#2a73cf', side: '#17448c' }, // bleu
    { top: '#a7a3fa', bot: '#6450d0', side: '#372a8c' }, // violet
    { top: '#b5e8fb', bot: '#5fabe5', side: '#2966a2' }, // bleu ciel
    { top: '#f77564', bot: '#da2729', side: '#87171f' }, // rouge
    { top: '#ffe067', bot: '#f5891b', side: '#ae5a0b' }  // jaune
  ];
  const DEFAULTS = {
    fontFamily: "'Luckiest Guy','Rubik','M PLUS Rounded 1c','Black Han Sans',sans-serif",
    fontWeight: 900,          // n'agit que sur les polices de secours (pas de faux gras)
    palette: PALETTE,
    outline: 0.066,            // contour noir visible, en em
    outlineColor: '#160d1d',
    extrude: [-0.045, 0.085], // profondeur 3D (vers le bas-gauche), en em
    rim: 0.024,                // liseré clair intérieur, en em
    tracking: 0.03,            // espace entre lettres, en em (proportionnel à la taille)
    arch: 0.05,              // hauteur de l'arche / largeur de la ligne
    jitter: 0.03,             // variation de taille des lettres
    shadow: 'rgba(45,30,70,.30)',
    minEmPx: 46,
    maxHeight: null,          // px, ou fonction () => px (ex. () => innerHeight * .35)              // en dessous → essai sur 2 lignes
    maxLines: 2,
    lang: null                // pour la mise en majuscules (ex. 'tr')
  };

  const F = 100;              // unité interne : 1em = 100 unités SVG
  const RTL = /[\u0590-\u08FF\uFB1D-\uFDFF\uFE70-\uFEFF]/;
  const SVGNS = 'http://www.w3.org/2000/svg';
  let uid = 0;

  function segments(text) {
    if (global.Intl && Intl.Segmenter) {
      return Array.from(new Intl.Segmenter(undefined, { granularity: 'grapheme' }).segment(text), s => s.segment);
    }
    return Array.from(text);
  }

  // Découpe en unités dessinables (lettres, ou mots pour les écritures liées)
  function tokenize(text) {
    const rtl = RTL.test(text);
    const out = [];
    const parts = rtl ? text.split(/(\s+)/).filter(Boolean) : segments(text);
    for (const p of parts) {
      if (/^[\s]+$/.test(p)) {
        const narrow = /^[\u202F\u2009\u200A]+$/.test(p);
        out.push({ space: true, narrow, breakable: !/[\u00A0\u202F]/.test(p) });
      } else out.push({ ch: p });
    }
    return { units: out, rtl };
  }

  // Mesure de chaque glyphe avec la vraie police
  function measure(units, o) {
    const svg = document.createElementNS(SVGNS, 'svg');
    svg.setAttribute('style', 'position:absolute;left:-9999px;top:0;visibility:hidden');
    document.body.appendChild(svg);
    for (const u of units) {
      if (u.space) { u.w = (u.narrow ? 0.24 : 0.28) * F; continue; }
      const t = document.createElementNS(SVGNS, 'text');
      t.setAttribute('font-size', F);
      t.setAttribute('style', `font-family:${o.fontFamily};font-weight:${o.fontWeight};font-synthesis:none${RTL.test(u.ch) ? ';direction:rtl;unicode-bidi:embed' : ''}`);
      t.textContent = u.ch;
      svg.appendChild(t);
      u.w = t.getComputedTextLength();
      const bb = t.getBBox();
      u.cy = bb.y + bb.height * 0.5;   // centre de rotation
    }
    svg.remove();
  }

  // Coupe en lignes (1 ou 2) au meilleur espace sécable
  function splitLines(units, n) {
    if (n === 1) return [units];
    let best = null;
    units.forEach((u, i) => {
      if (!u.space || !u.breakable) return;
      const a = units.slice(0, i), b = units.slice(i + 1);
      const wa = a.reduce((s, x) => s + x.w, 0), wb = b.reduce((s, x) => s + x.w, 0);
      const cost = Math.max(wa, wb);
      if (!best || cost < best.cost) best = { cost, lines: [a, b] };
    });
    return best ? best.lines : null;
  }

  // Positions : arche + rotation tangente
  function place(lines, o, rtl) {
    const placed = [];
    let k = 0;
    lines.forEach((line, li) => {
      const seq = rtl ? line.slice().reverse() : line;
      let x = 0;
      const pos = [];
      seq.forEach(u => {
        pos.push({ u, cx: x + u.w / 2 });
        x += u.w + (u.space ? 0 : o.tracking * F);
      });
      const W = x - o.tracking * F;
      const A = o.arch * W;
      const baseY = li * F * 1.3;
      pos.forEach(p => {
        p.cx -= W / 2;
        if (p.u.space) return;
        const t = W > 0 ? (2 * p.cx) / W : 0;
        p.y = baseY - A * (1 - t * t);
        p.rot = Math.atan(4 * o.arch * t) * 180 / Math.PI;
        p.idx = k;
        p.s = 1 + o.jitter * Math.sin(k * 2.17 + 0.6);
        k++;
        placed.push(p);
      });
    });
    return placed;
  }

  function darken(hex, f) {
    const n = parseInt(hex.slice(1), 16);
    const c = [n >> 16, (n >> 8) & 255, n & 255].map(v => Math.round(v * (1 - f)));
    return '#' + c.map(v => v.toString(16).padStart(2, '0')).join('');
  }

  function lighten(hex, f) {
    const n = parseInt(hex.slice(1), 16);
    const c = [n >> 16, (n >> 8) & 255, n & 255].map(v => Math.round(v + (255 - v) * f));
    return '#' + c.map(v => v.toString(16).padStart(2, '0')).join('');
  }

  function esc(s) { return s.replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c])); }

  function buildSVG(placed, o, label, rtl) {
    const id = 'ct' + (++uid);
    const pal = o.palette;
    const ex = o.extrude[0] * F, ey = o.extrude[1] * F;
    const ow = o.outline * 2 * F;
    const steps = 8;
    const style = `font-family:${esc(o.fontFamily)};font-weight:${o.fontWeight};font-synthesis:none${rtl ? ';direction:rtl;unicode-bidi:embed' : ''}`;
    let defs = '', shadow = '', body = '';

    placed.forEach((p, i) => {
      const c = pal[p.idx % pal.length];
      const g = `${id}g${i}`;
      const T = `translate(${p.cx.toFixed(2)} ${p.y.toFixed(2)}) rotate(${p.rot.toFixed(2)} 0 ${p.u.cy.toFixed(2)}) scale(${p.s.toFixed(3)})`;
      defs += `<text id="${g}" x="0" y="0" text-anchor="middle" font-size="${F}" style="${style}">${esc(p.u.ch)}</text>`;
      defs += `<linearGradient id="${g}f" x1="0" y1="0" x2="0" y2="1"><stop offset=".12" stop-color="${c.top}"/><stop offset=".92" stop-color="${c.bot}"/></linearGradient>`;

      // ombre portée douce
      shadow += `<use href="#${g}" transform="translate(${(ex * 0.6).toFixed(2)} ${(ey + 0.05 * F).toFixed(2)}) ${T}" stroke-width="${ow}"/>`;

      // contour noir englobant face + tranche
      let s = '';
      for (const f of [1, 0.66, 0.33, 0]) {
        s += `<use href="#${g}" transform="translate(${(ex * f).toFixed(2)} ${(ey * f).toFixed(2)}) ${T}" fill="${o.outlineColor}" stroke="${o.outlineColor}" stroke-width="${ow}" stroke-linejoin="round"/>`;
      }
      // tranche 3D (du plus profond au plus proche)
      const sideDark = darken(c.side, 0.25);
      for (let j = steps; j >= 1; j--) {
        const f = j / steps;
        const col = j > steps * 0.6 ? sideDark : c.side;
        s += `<use href="#${g}" transform="translate(${(ex * f).toFixed(2)} ${(ey * f).toFixed(2)}) ${T}" fill="${col}" stroke="${col}" stroke-width="${(0.012 * F).toFixed(2)}" stroke-linejoin="round"/>`;
      }
      // face en dégradé + liseré clair intérieur
      s += `<use href="#${g}" transform="${T}" fill="url(#${g}f)" filter="url(#${id}r)"/>`;
      body += `<g>${s}</g>`;
    });

    const blur = 0.022 * F;
    // liseré clair intérieur : bord de la forme obtenu par érosion (sans artefact de contours superposés)
    defs += `<filter id="${id}r" x="-5%" y="-5%" width="110%" height="110%" color-interpolation-filters="sRGB">` +
      `<feMorphology in="SourceAlpha" operator="erode" radius="${(o.rim * F).toFixed(2)}" result="e"/>` +
      `<feComposite in="SourceAlpha" in2="e" operator="out" result="edge"/>` +
      `<feFlood flood-color="#fff" flood-opacity=".42"/><feComposite in2="edge" operator="in" result="rim"/>` +
      `<feMerge><feMergeNode in="SourceGraphic"/><feMergeNode in="rim"/></feMerge></filter>`;
    defs += `<filter id="${id}b" x="-10%" y="-30%" width="120%" height="160%"><feGaussianBlur stdDeviation="${blur}"/></filter>`;
    return `<svg xmlns="${SVGNS}" role="img" aria-label="${esc(label)}" preserveAspectRatio="xMidYMid meet" style="display:block;width:100%;height:auto;overflow:visible"><title>${esc(label)}</title><defs>${defs}</defs>` +
      `<g filter="url(#${id}b)" fill="${o.shadow}" stroke="${o.shadow}" stroke-linejoin="round">${shadow}</g>${body}</svg>`;
  }

  function fitViewBox(svg) {
    const bb = svg.getBBox();
    const pad = 0.04 * F;
    svg.setAttribute('viewBox', `${(bb.x - pad).toFixed(1)} ${(bb.y - pad).toFixed(1)} ${(bb.width + 2 * pad).toFixed(1)} ${(bb.height + 2 * pad).toFixed(1)}`);
    return bb;
  }

  // Construit les variantes 1/2 lignes et garde la plus lisible
  function layout(host) {
    const st = host._ct;
    const W = host.clientWidth || 320;
    const mh = typeof st.o.maxHeight === 'function' ? st.o.maxHeight() : st.o.maxHeight;
    const maxH = mh > 0 ? mh : Infinity;
    const cands = [];
    for (let n = 1; n <= st.o.maxLines; n++) {
      const lines = splitLines(st.units, n);
      if (!lines) continue;
      const lw = Math.max(...lines.map(l => l.reduce((s, u) => s + u.w, 0))) * 1.08 + 0.25 * F;
      const lh = (n * 1.3 + 0.3) * F;
      const emPx = Math.min(W / lw, maxH / lh) * F;
      cands.push({ n, lines, emPx });
    }
    let pick = cands[0];
    if (pick.emPx < st.o.minEmPx) cands.forEach(c => { if (c.emPx > pick.emPx * 1.15) pick = c; });
    const cap = isFinite(maxH) ? maxH + 'px' : '';
    if (st.lastN === pick.n && host.firstChild) { host.firstChild.style.maxHeight = cap; return; }
    st.lastN = pick.n;
    host.innerHTML = buildSVG(place(pick.lines, st.o, st.rtl), st.o, st.text, st.rtl);
    const svg = host.firstChild;
    fitViewBox(svg);
    svg.style.maxHeight = cap;
    host.dataset.lines = pick.n;
  }

  async function render(host, text, options) {
    const o = Object.assign({}, DEFAULTS, host._ct ? host._ct.o : null, options || {});
    const shown = o.lang ? text.toLocaleUpperCase(o.lang) : text;
    if (document.fonts && document.fonts.load) {
      await Promise.all(o.fontFamily.split(',').map(f =>
        document.fonts.load(`${o.fontWeight} ${F}px ${f.trim()}`, shown).catch(() => {})));
    }
    const { units, rtl } = tokenize(shown);
    measure(units, o);
    host._ct = { text: shown, o, units, rtl, lastN: 0 };
    host.innerHTML = '';
    layout(host);
    if (!host._ctRO && global.ResizeObserver) {
      host._ctRO = new ResizeObserver(() => host._ct && layout(host));
      host._ctRO.observe(host);
      global.addEventListener('resize', () => host._ct && layout(host));
    }
  }

  global.CartoonText = { render, update: (h, t) => render(h, t), PALETTE, DEFAULTS };
})(window);
