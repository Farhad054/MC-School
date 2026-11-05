import assert from 'node:assert/strict';
import {primeMath, renderMath, richText, normalizeMath} from './math.mjs';

const cases = [
  [String.raw`\frac{x+2}{x-3}`, 'mfrac'],
  [String.raw`(2x-3)^2`, 'msup'],
  [String.raw`\frac{x^2-9}{x-3}=x+3`, 'mfrac'],
  [String.raw`\sqrt{x+4}=3`, 'msqrt'],
];
await primeMath(cases.map(([latex])=>({latex})));
for(const [latex,node] of cases) {
  const svg=renderMath({latex});
  assert.match(svg, /<svg\b/);
  assert.ok(svg.includes(`data-mml-node="${node}"`),`${latex}: expected mathematical ${node} structure`);
  assert.doesNotMatch(svg,/data-mml-node="merror"|data-mjx-error/);
}
await primeMath({type:'richText',content:[{math:'y=2x-1'},{math:'y=5-x'}]});
assert.deepEqual(normalizeMath(['1/2','=','2/4']),{tokens:['1/2','=','2/4']});
assert.match(renderMath({tokens:['1/2','=','2/4']}),/math-fraction/);
assert.throws(()=>normalizeMath({latex:'x',tokens:['x']}),/exactly one/);
assert.throws(()=>normalizeMath({latex:String.raw`\\frac{x}{2}`}),/one backslash/);
assert.throws(()=>normalizeMath({latex:String.raw`\href{https://example.com}{x}`}),/Unsafe/);
await assert.rejects(()=>primeMath({latex:String.raw`\frac{x}{`}),/Invalid LaTeX/);
assert.match(richText({type:'richText',content:[{text:'<script>'},{math:'y=2x-1'}]}),/&lt;script&gt;/);
assert.equal((richText({type:'richText',content:[{text:'Schnittpunkt von '},{math:'y=2x-1'},{text:' und '},{math:'y=5-x'}]}).match(/<svg\b/g)||[]).length,2);
console.log('PASS production MathJax: fractions, powers, roots, equations, inline math, tokens and malformed/unsafe input');
