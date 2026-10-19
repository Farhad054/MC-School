/**
 * Mindcrafti mathematical diagram components, rendered as resolution-independent SVG.
 * This is a deliberately constrained data-to-graphics engine, not arbitrary user SVG/JS.
 * No browser calls, npm packages, network access, or eval() required.
 * Coordinate geometry is drawn to scale; graph axes use the same physical scale for x/y.
 */
const NAVY = '#070a20';
const ORANGE = '#ff6a00';
const GREEN = '#007e86';
const GRID = '#dce3ed';
const FAINT = '#f8fafc';
const MUTED = '#697488';
let plotId = 0;

const esc = s => String(s ?? '').replaceAll('&', '&amp;').replaceAll('<', '&lt;')
  .replaceAll('>', '&gt;').replaceAll('"', '&quot;').replaceAll("'", '&#39;');
const fmt = n => (Math.abs(n) < 1e-8 ? '0' : Number(n.toFixed(3)).toString());
function finite(value, field) {
  if (typeof value !== 'number' || !Number.isFinite(value) || Math.abs(value) > 10000) {
    throw new Error(`${field} must be a finite, bounded number`);
  }
  return value;
}
function point(p, field) {
  if (Array.isArray(p) && p.length === 2) {
    return [finite(p[0], `${field}.x`), finite(p[1], `${field}.y`)];
  }
  if (p && typeof p === 'object') {
    return [finite(p.x, `${field}.x`), finite(p.y, `${field}.y`)];
  }
  throw new Error(`${field}: expected [x,y] or {x,y}`);
}
function range(input, field) {
  if (!Array.isArray(input) || input.length !== 2) throw new Error(`${field} must be [min,max]`);
  const [lo,hi] = input.map((v,i) => finite(v, `${field}.${i}`));
  if (!(lo < hi) || hi - lo > 50 || hi - lo < 0.5) throw new Error(`${field}: invalid bounds`);
  return [lo,hi];
}
function banner(label, svg, caption = '') {
  return `<section class="block diagram-block"><div class="diagram-eyebrow">${esc(label ?? 'ABBILDUNG')}</div>${svg}${caption ? `<div class="diagram-caption">${esc(caption)}</div>` : ''}</section>`;
}
const vec = (x,y) => `${fmt(x)},${fmt(y)}`;
const len = v => Math.hypot(v[0],v[1]);
const sub = (a,b) => [a[0]-b[0],a[1]-b[1]];
const add = (a,b) => [a[0]+b[0],a[1]+b[1]];
const mul = (v,k) => [v[0]*k,v[1]*k];
function unit(v) { const d=len(v);return d < 1e-8 ? [0,0] : mul(v,1/d); }

/** Typed, safely evaluated function definition. Constant-first polynomial coeffs. */
export function functionAt(series, x) {
  finite(x,'x');
  let y;
  switch (series.kind) {
    case 'linear':
      y = finite(series.m, 'm')*x+finite(series.b, 'b');break;
    case 'quadratic':
      y = finite(series.a, 'a')*x*x + finite(series.b, 'b')*x+finite(series.c,'c');break;
    case 'polynomial': {
      if (!Array.isArray(series.coefficients) || series.coefficients.length < 1 || series.coefficients.length > 5)
        throw new Error('polynomial coefficients must have length 1–5');
      y = [...series.coefficients].reverse().reduce((sum, c) => sum*x+finite(c,'coefficient'),0);break;
    }
    case 'sine':
    case 'cosine': {
      const a=finite(series.amplitude,'amplitude');
      const freq=finite(series.frequency,'frequency');
      const phase=finite(series.phase ?? 0,'phase');
      const shift=finite(series.offset ?? 0,'offset');
      y= a*(series.kind==='sine'?Math.sin(freq*x+phase):Math.cos(freq*x+phase))+shift;break;
    }
    default: throw new Error(`Unsupported plot function: ${esc(series.kind)}`);
  }
  return Number.isFinite(y) ? y : null;
}

export function graphSvg(spec) {
  if (!spec || !Array.isArray(spec.series) || spec.series.length < 1 || spec.series.length > 3)
    throw new Error('graph requires 1–3 function series');
  const [x0,x1]=range(spec.xRange,'xRange');
  const [y0,y1]=range(spec.yRange,'yRange');
  const step=finite(spec.gridStep ?? 1,'gridStep');
  if(step < 0.25 || step > 10 || (x1-x0)/step > 35 || (y1-y0)/step > 35)throw Error('Unsupported gridStep');
  const W=560,H=258;
  const scale=Math.min(455/(x1-x0),207/(y1-y0));
  const plotW=scale*(x1-x0), plotH=scale*(y1-y0);
  const left=(W-plotW)/2, top=(H-plotH)/2-2;
  const right=left+plotW, bottom=top+plotH;
  const X=x => left + (x-x0)*scale;
  const Y=y => bottom - (y-y0)*scale;
  const inside=(x,y)=>x>=x0-1e-9 && x<=x1+1e-9 && y>=y0-1e-9 && y<=y1+1e-9;
  const id=`mc-graph-clip-${++plotId}`;
  const grid=[];
  const xlabelY=Math.min(H-3,bottom+14);
  const ylabelX=Math.max(7,left-10);
  for(let k=Math.ceil(x0/step);k<=Math.floor(x1/step);k++){
    const x=k*step;const px=X(x);
    grid.push(`<line x1="${fmt(px)}" y1="${fmt(top)}" x2="${fmt(px)}" y2="${fmt(bottom)}" stroke="${GRID}" stroke-width="0.8"/>`);
    if (Number.isInteger(x) && (Math.abs(x)<1e-9 || Math.round(x)%2===0 || (x1-x0)<=9)) {
      grid.push(`<text x="${fmt(px)}" y="${fmt(xlabelY)}" text-anchor="middle" font-size="9.5" fill="${MUTED}">${fmt(x)}</text>`);
    }
  }
  for(let k=Math.ceil(y0/step);k<=Math.floor(y1/step);k++){
    const y=k*step,py=Y(y);
    grid.push(`<line x1="${fmt(left)}" y1="${fmt(py)}" x2="${fmt(right)}" y2="${fmt(py)}" stroke="${GRID}" stroke-width="0.8"/>`);
    if(Number.isInteger(y) && (Math.abs(y)<1e-9 || Math.round(y)%2===0 || (y1-y0)<=9)){
      grid.push(`<text x="${fmt(ylabelX)}" y="${fmt(py+3)}" text-anchor="end" font-size="9.5" fill="${MUTED}">${fmt(y)}</text>`);
    }
  }
  const axes=[];
  if(x0<=0&&x1>=0)axes.push(`<line x1="${fmt(X(0))}" y1="${fmt(top-1)}" x2="${fmt(X(0))}" y2="${fmt(bottom+1)}" stroke="${NAVY}" stroke-width="1.6"/>`);
  if(y0<=0&&y1>=0)axes.push(`<line x1="${fmt(left-1)}" y1="${fmt(Y(0))}" x2="${fmt(right+1)}" y2="${fmt(Y(0))}" stroke="${NAVY}" stroke-width="1.6"/>`);
  const curves=[];
  spec.series.forEach((s,i)=>{
    const color=i===0?ORANGE:i===1?GREEN:NAVY;
    const n=340;
    const pts=[];
    for(let j=0;j<=n;j++){
      const x=x0+(x1-x0)*j/n;
      const y=functionAt(s,x);
      if (y===null) continue;
      pts.push([X(x),Y(y)]);
    }
    curves.push(`<polyline points="${pts.map(p=>vec(...p)).join(' ')}" stroke="${color}" stroke-width="2.6" fill="none" stroke-linecap="round" stroke-linejoin="round"/>`);
  });
  const markers=[];
  if(spec.points!==undefined){
    if(!Array.isArray(spec.points) || spec.points.length>12)throw Error('Max 12 graph points');
    spec.points.forEach((p,i)=>{
      const coord=point(p,'graph.point'); const [x,y]=coord;
      if(!inside(x,y))throw Error(`graph point ${i} outside axes`);
      if(p.seriesIndex!==undefined){
        if(!Number.isInteger(p.seriesIndex)||p.seriesIndex<0||p.seriesIndex>=spec.series.length)throw Error('Invalid seriesIndex');
        if(Math.abs(functionAt(spec.series[p.seriesIndex],x)-y)>1e-5)throw Error('Graph marker not on selected function');
      }
      const px=X(x),py=Y(y);
      markers.push(`<circle cx="${fmt(px)}" cy="${fmt(py)}" r="3.4" fill="${NAVY}" stroke="white" stroke-width="1"/>`);
      if(p.label)markers.push(`<text x="${fmt(px+7)}" y="${fmt(py-6)}" font-size="11" fill="${NAVY}">${esc(p.label)}</text>`);
    });
  }
  const legend=spec.series.map((s,i)=>{
    const color=i===0?ORANGE:i===1?GREEN:NAVY;
    return `<span class="graph-legend-item"><i class="graph-legend-swatch" style="background:${color}"></i>${esc(s.label??`f${i+1}(x)`)}</span>`;
  }).join('');
  return `<div class="graph-legend">${legend}</div><svg class="diagram-svg" viewBox="0 0 ${W} ${H}" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="${esc(spec.alt || 'Koordinatensystem mit Funktionsgraphen')}">
<defs><clipPath id="${id}"><rect x="${fmt(left)}" y="${fmt(top)}" width="${fmt(plotW)}" height="${fmt(plotH)}"/></clipPath></defs>
<rect x="${fmt(left)}" y="${fmt(top)}" width="${fmt(plotW)}" height="${fmt(plotH)}" fill="${FAINT}" stroke="${GRID}" stroke-width="1"/>
${grid.join('')}${axes.join('')}<g clip-path="url(#${id})">${curves.join('')}</g>${markers.join('')}
<text x="${fmt(Math.min(W-5,right+12))}" y="${fmt(y0<=0&&y1>=0?Y(0)-5:bottom+10)}" font-size="11" fill="${NAVY}">x</text>
<text x="${fmt(x0<=0&&x1>=0?X(0)+6:left+6)}" y="${fmt(top+12)}" font-size="11" fill="${NAVY}">y</text>
</svg>`;
}

/** geometry figures are built from measured coordinates, not hand-drawn paths. */
export function geometrySvg(spec) {
  if(!spec || !['triangle','rectangle','circle','quadrilateral','cube','cuboid'].includes(spec.figure))
    throw new Error(`Unsupported geometry figure: ${esc(spec?.figure)}`);
  const W=560,H=258;
  let world=[], entries=[];
  const circle = spec.figure==='circle';
  const is3d = spec.figure==='cube' || spec.figure==='cuboid';
  let center,radius;
  if(is3d){
    world=[[0,0],[1,1]];
  } else if(circle){
    center=point(spec.center,'center');radius=finite(spec.radius,'radius');
    if(radius<=0)throw Error('Circle radius must be positive');
    world=[add(center,[-radius,-radius]),add(center,[radius,radius])];
  }else{
    if(!Array.isArray(spec.vertices) || spec.vertices.length!==(spec.figure==='triangle'?3:4))throw Error(`Invalid ${spec.figure} vertices`);
    entries=spec.vertices.map((p,i)=>({name: String(p.label || 'ABCD'[i]), p:point(p,`vertex${i}`)}));
    world=entries.map(x=>x.p);
    const area=Math.abs(world.reduce((sum,p,i)=>sum+p[0]*world[(i+1)%world.length][1]-p[1]*world[(i+1)%world.length][0],0))/2;
    if(area<1e-5)throw Error('Degenerate polygon: area must be nonzero');
    const names=entries.map(e=>e.name);
    if(new Set(names).size!==names.length||names.some(x=>x.length>5))throw Error('Duplicate or invalid vertex labels');
    if(spec.figure==='rectangle'){
      const E=world.map((p,i)=>sub(world[(i+1)%4],p));
      const dot=(a,b)=>a[0]*b[0]+a[1]*b[1];
      const tol=1e-7;
      for(let i=0;i<4;i++){
        if(Math.abs(dot(E[i],E[(i+1)%4]))>tol*len(E[i])*len(E[(i+1)%4]))
          throw Error('Rectangle vertices are not perpendicular');
      }
      if(Math.abs(len(E[0])-len(E[2]))>tol || Math.abs(len(E[1])-len(E[3]))>tol)
        throw Error('Rectangle opposite sides have different lengths');
    }
  }
  const xs=world.map(p=>p[0]),ys=world.map(p=>p[1]);
  let xmin=Math.min(...xs),xmax=Math.max(...xs),ymin=Math.min(...ys),ymax=Math.max(...ys);
  let dx=xmax-xmin,dy=ymax-ymin;
  if(dx<0.001)dx=1;if(dy<0.001)dy=1;
  const s=Math.min(380/dx,172/dy);
  const cx=(xmin+xmax)/2,cy=(ymin+ymax)/2;
  const P=pt=>[W/2+(pt[0]-cx)*s,H/2-(pt[1]-cy)*s];
  const lab=(x,y,t,color=NAVY,anchor='middle')=>`<text x="${fmt(x)}" y="${fmt(y)}" text-anchor="${anchor}" font-size="15" font-family="DejaVu Sans, Arial, sans-serif" font-weight="700" fill="${color}">${esc(t)}</text>`;
  const unitSuffix=String(spec.unit??'');
  if(unitSuffix.length>8)throw Error('unit label too long');
  const shapes=[];
  if(is3d){
    const dimLine=(x1,y1,x2,y2,label,lx,ly,anchor='middle')=>`<line x1="${fmt(x1)}" y1="${fmt(y1)}" x2="${fmt(x2)}" y2="${fmt(y2)}" stroke="${ORANGE}" stroke-width="2.1"/>
      <circle cx="${fmt(x1)}" cy="${fmt(y1)}" r="3" fill="${ORANGE}"/><circle cx="${fmt(x2)}" cy="${fmt(y2)}" r="3" fill="${ORANGE}"/>
      ${lab(lx,ly,label,ORANGE,anchor)}`;
    const drawBox=(opts)=>{
      const x=opts.x,y=opts.y,w=opts.w,h=opts.h,d=opts.d,name=opts.name;
      const dx=d*0.78, dy=-d*0.55;
      const A=[x,y],B=[x+w,y],C=[x+w,y+h],D=[x,y+h];
      const E=[x+dx,y+dy],F=[x+w+dx,y+dy],G=[x+w+dx,y+h+dy],H=[x+dx,y+h+dy];
      const q=(p)=>vec(...p);
      const out=[];
      out.push(`<polygon points="${q(A)} ${q(B)} ${q(C)} ${q(D)}" fill="#fff9f4" stroke="${NAVY}" stroke-width="2.4" stroke-linejoin="round"/>`);
      out.push(`<polygon points="${q(D)} ${q(C)} ${q(G)} ${q(H)}" fill="#fff6ee" stroke="${NAVY}" stroke-width="2.4" stroke-linejoin="round"/>`);
      out.push(`<polygon points="${q(B)} ${q(C)} ${q(G)} ${q(F)}" fill="#fff3ea" stroke="${NAVY}" stroke-width="2.4" stroke-linejoin="round"/>`);
      out.push(`<line x1="${fmt(A[0])}" y1="${fmt(A[1])}" x2="${fmt(E[0])}" y2="${fmt(E[1])}" stroke="${NAVY}" stroke-width="2.2"/>`);
      out.push(`<line x1="${fmt(E[0])}" y1="${fmt(E[1])}" x2="${fmt(F[0])}" y2="${fmt(F[1])}" stroke="${NAVY}" stroke-width="2.2"/>`);
      out.push(`<line x1="${fmt(F[0])}" y1="${fmt(F[1])}" x2="${fmt(G[0])}" y2="${fmt(G[1])}" stroke="${NAVY}" stroke-width="2.2"/>`);
      out.push(`<line x1="${fmt(E[0])}" y1="${fmt(E[1])}" x2="${fmt(H[0])}" y2="${fmt(H[1])}" stroke="${NAVY}" stroke-width="1.8" stroke-dasharray="7 5"/>`);
      out.push(`<line x1="${fmt(H[0])}" y1="${fmt(H[1])}" x2="${fmt(G[0])}" y2="${fmt(G[1])}" stroke="${NAVY}" stroke-width="1.8" stroke-dasharray="7 5"/>`);
      out.push(`<line x1="${fmt(H[0])}" y1="${fmt(H[1])}" x2="${fmt(D[0])}" y2="${fmt(D[1])}" stroke="${NAVY}" stroke-width="1.8" stroke-dasharray="7 5"/>`);
      if(opts.showMeasures === true){
        out.push(dimLine(A[0],A[1]+16,B[0],B[1]+16,`${fmt(opts.widthVal)}${unitSuffix?` ${unitSuffix}`:''}`,(A[0]+B[0])/2,A[1]+34));
        out.push(dimLine(B[0]+18,B[1]+10,E[0]+18,E[1]+10,`${fmt(opts.depthVal)}${unitSuffix?` ${unitSuffix}`:''}`,(B[0]+E[0])/2+28,(B[1]+E[1])/2+8));
        out.push(dimLine(C[0]+20,C[1],G[0]+20,G[1],`${fmt(opts.heightVal)}${unitSuffix?` ${unitSuffix}`:''}`,G[0]+42,(C[1]+G[1])/2+4,'start'));
      }
      out.push(lab((A[0]+B[0])/2,C[1]+40,name,NAVY));
      return out.join('');
    };
    if(spec.figure==='cube'){
      const side=finite(spec.side??4,'side');
      shapes.push(drawBox({x:150,y:120,w:92,h:92,d:42,name:spec.name??'Würfel',widthVal:side,depthVal:side,heightVal:side,showMeasures: spec.showMeasuresOnFigure}));
    } else {
      const widthVal=finite(spec.width??6,'width'), depthVal=finite(spec.depth??4,'depth'), heightVal=finite(spec.height??3,'height');
      shapes.push(drawBox({x:105,y:118,w:172,h:76,d:56,name:spec.name??'Quader',widthVal,depthVal,heightVal,showMeasures: spec.showMeasuresOnFigure}));
    }
  } else if(circle){
    const cp=P(center),rad=radius*s;
    shapes.push(`<circle cx="${fmt(cp[0])}" cy="${fmt(cp[1])}" r="${fmt(rad)}" fill="#fff8f2" stroke="${NAVY}" stroke-width="2.4"/>`);
    shapes.push(`<circle cx="${fmt(cp[0])}" cy="${fmt(cp[1])}" r="3.4" fill="${ORANGE}"/>`);
    const angle=(Math.PI/180)*finite(spec.radiusAngle??30,'radiusAngle');
    const rp=[cp[0]+rad*Math.cos(angle),cp[1]-rad*Math.sin(angle)];
    shapes.push(`<line x1="${fmt(cp[0])}" y1="${fmt(cp[1])}" x2="${fmt(rp[0])}" y2="${fmt(rp[1])}" stroke="${ORANGE}" stroke-width="2.2"/>`);
    shapes.push(lab(cp[0]-14,cp[1]+16,spec.centerLabel??'M'));
    const radiusLabel=spec.showRadiusMeasureOnFigure===true
      ? `r = ${fmt(radius)}${unitSuffix?` ${unitSuffix}`:''}` : 'r';
    shapes.push(lab((cp[0]+rp[0])/2+4,(cp[1]+rp[1])/2-12,radiusLabel,ORANGE));
  }else{
    const points=world.map(P);
    const centroid=points.reduce((acc,p)=>add(acc,p),[0,0]).map(x=>x/points.length);
    const showVertexLabels = spec.showVertexLabels !== false;
    const showSideLabels = spec.showSideLabels !== false;
    const showAngleLabels = spec.showAngleLabels !== false;
    const showSideMeasures = spec.showSides === true;
    const showAngleMeasures = spec.showAngles === true;
    const defaultSideLabels = points.length===3 ? ['c','a','b'] : ['a','b','c','d'];
    const defaultAngleLabels = points.length===3 ? ['α','β','γ'] : entries.map(e => `∠${e.name}`);
    const sideLabels = Array.isArray(spec.sideLabels) ? spec.sideLabels : defaultSideLabels;
    const angleLabels = Array.isArray(spec.angleLabels) ? spec.angleLabels : defaultAngleLabels;
    shapes.push(`<polygon points="${points.map(p=>vec(...p)).join(' ')}" fill="#fff9f4" stroke="${NAVY}" stroke-width="2.5" stroke-linejoin="round"/>`);
    entries.forEach((e,i)=>{
      const pt=points[i];const dir=unit(sub(pt,centroid));const offset=mul(dir,15);
      shapes.push(`<circle cx="${fmt(pt[0])}" cy="${fmt(pt[1])}" r="3.5" fill="${ORANGE}"/>`);
      if(showVertexLabels){
        shapes.push(lab(pt[0]+offset[0],pt[1]+offset[1]+2,e.name));
      }
    });
    if(showSideLabels || showSideMeasures){
      points.forEach((pt,i)=>{
        const next=points[(i+1)%points.length];
        const mid=mul(add(pt,next),0.5),normal=unit(sub(mid,centroid));
        const sidePos=add(mid,mul(normal,18));
        const measPos=add(mid,mul(normal,34));
        if(showSideLabels && sideLabels[i]){
          shapes.push(lab(sidePos[0],sidePos[1],String(sideLabels[i]),GREEN));
        }
        if(showSideMeasures){
          const d=len(sub(world[i],world[(i+1)%world.length]));
          shapes.push(lab(measPos[0],measPos[1],`${fmt(d)}${unitSuffix?` ${unitSuffix}`:''}`,GREEN));
        }
      });
    }
    if(showAngleLabels || showAngleMeasures){
      points.forEach((p,i)=>{
        const prev=points[(i+points.length-1)%points.length];
        const next=points[(i+1)%points.length];
        const a=unit(sub(prev,p)),b=unit(sub(next,p));
        const angle=Math.acos(Math.max(-1,Math.min(1,a[0]*b[0]+a[1]*b[1])));
        const bis=unit(add(a,b));
        if(len(bis)<1e-5)return;
        const arcR=20;
        const sweep=Math.atan2(a[0]*b[1]-a[1]*b[0],a[0]*b[0]+a[1]*b[1]);
        const start=Math.atan2(a[1],a[0]);
        const arc=Array.from({length:17},(_,k)=>{
          const theta=start+sweep*k/16;return add(p,[arcR*Math.cos(theta),arcR*Math.sin(theta)]);
        });
        shapes.push(`<polyline points="${arc.map(pt=>vec(...pt)).join(' ')}" stroke="${ORANGE}" stroke-width="1.6" fill="none"/>`);
        const baseLbl=add(p,mul(bis,40));
        const measLbl=add(p,mul(bis,58));
        if(showAngleLabels && angleLabels[i]){
          shapes.push(lab(baseLbl[0],baseLbl[1]+3,String(angleLabels[i]),ORANGE));
        }
        if(showAngleMeasures){
          shapes.push(lab(measLbl[0],measLbl[1]+4,`${fmt(Math.round(angle*180/Math.PI*10)/10)}°`,ORANGE));
        }
      });
    }
  }
  return `<svg class="diagram-svg" viewBox="0 0 ${W} ${H}" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="${esc(spec.alt ?? `${spec.figure} drawing`)}">${shapes.join('')}</svg>`;
}

export function renderDiagram(block){
  switch(block.type){
    case 'graph':return banner(block.label??'FUNKTION & KOORDINATENSYSTEM',graphSvg(block),block.caption);
    case 'geometry':return banner(block.label??'GEOMETRISCHE ZEICHNUNG',geometrySvg(block),block.caption);
    default:throw Error('Unsupported diagram block');
  }
}
