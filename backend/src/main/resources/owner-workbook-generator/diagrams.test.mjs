import assert from 'node:assert/strict';
import { functionAt, graphSvg, geometrySvg, renderDiagram } from './diagrams.mjs';

assert.equal(functionAt({kind:'linear',m:2,b:1},3),7);
assert.equal(functionAt({kind:'quadratic',a:1,b:0,c:-4},2),0);
assert.equal(functionAt({kind:'polynomial',coefficients:[1,2,3]},2),17);
assert.ok(Math.abs(functionAt({kind:'sine',amplitude:2,frequency:1},Math.PI/2)-2)<1e-10);
assert.ok(Math.abs(functionAt({kind:'cosine',amplitude:3,frequency:1},0)-3)<1e-10);
const line={type:'graph',label:'LINEAR',xRange:[-5,5],yRange:[-5,5],series:[{kind:'linear',m:1,b:0,label:'f(x) = x'}],points:[{x:2,y:2,label:'P'}]};
const grid=graphSvg(line);
assert.ok(grid.includes('clipPath'));
assert.ok(grid.includes('polyline'));
assert.ok(grid.includes('graph-legend'));
assert.ok(grid.includes('P'));
assert.throws(()=>graphSvg({...line,points:[{x:2,y:3,seriesIndex:0}]}),/not on/);
assert.throws(()=>graphSvg({...line,xRange:[-1000,1000]}),/bounds/);
assert.throws(()=>graphSvg({...line,series:[{kind:'javascript',body:'alert(1)'}]}),/Unsupported/);
assert.ok(!renderDiagram({...line,label:'<script>bad</script>'}).includes('<script>'));
const triangle={type:'geometry',figure:'triangle',vertices:[{label:'A',x:0,y:0},{label:'B',x:6,y:0},{label:'C',x:0,y:8}],showSides:true,showAngles:true,unit:'cm'};
const svg=geometrySvg(triangle);
assert.ok(svg.includes('polygon'));
assert.ok(svg.includes('90°'));
assert.ok(svg.includes('10 cm'));
assert.throws(()=>geometrySvg({...triangle,vertices:[{x:0,y:0},{x:1,y:1},{x:2,y:2}]}),/Degenerate/);
assert.throws(()=>geometrySvg({type:'geometry',figure:'rectangle',vertices:[{x:0,y:0},{x:8,y:0},{x:7,y:4},{x:0,y:4}]}),/not perpendicular/);
assert.ok(geometrySvg({type:'geometry',figure:'circle',center:{x:0,y:0},radius:3,unit:'cm'}).includes('>r</text>')); 
assert.ok(geometrySvg({type:'geometry',figure:'rectangle',vertices:[{x:0,y:0},{x:8,y:0},{x:8,y:4},{x:0,y:4}],showSides:true}).includes('8'));
const rectErr={type:'geometry',figure:'rectangle',vertices:[{x:0,y:0},{x:8,y:0},{x:7,y:4},{x:0,y:4}]};
assert.throws(()=>geometrySvg(rectErr));

// Pilot v3: geometry labels are letters and angle symbols, never numeric by default.
const cleanTriangle={type:'geometry',figure:'triangle',vertices:[
  {label:'A',x:0,y:0},{label:'B',x:7,y:0},{label:'C',x:2,y:5}
],unit:'cm'};
const cleanSvg=geometrySvg(cleanTriangle);
for(const vertex of ['A','B','C']) assert.ok(cleanSvg.includes(`>${vertex}</text>`),`missing vertex ${vertex}`);
for(const side of ['a','b','c']) assert.ok(cleanSvg.includes(`>${side}</text>`),`missing side ${side}`);
for(const angle of ['α','β','γ']) assert.ok(cleanSvg.includes(`>${angle}</text>`),`missing angle ${angle}`);
assert.ok(!cleanSvg.includes('paint-order'), 'no unsupported white-stroke text paint order');
assert.ok(!cleanSvg.includes('cm</text>'), 'no numeric sides by default');
const visible=geometrySvg({...cleanTriangle,showSideLabels:false,showAngleLabels:false});
assert.ok(!visible.includes('>α</text>') && !visible.includes('>a</text>'));
const cube=geometrySvg({type:'geometry',figure:'cube',unit:'cm',side:4});
assert.ok(cube.includes('Würfel') && !cube.includes('4 cm</text>'));
const circleClean=geometrySvg({type:'geometry',figure:'circle',center:{x:0,y:0},radius:3,unit:'cm'});
assert.ok(circleClean.includes('>r</text>') && !circleClean.includes('r = 3 cm'));
assert.ok(geometrySvg({type:'geometry',figure:'circle',center:{x:0,y:0},radius:3,unit:'cm',showRadiusMeasureOnFigure:true}).includes('r = 3 cm'));

console.log('PASS diagrams: curves, validation, standard ABC/abc/αβγ, clean 3D, default numeric-label suppression');
