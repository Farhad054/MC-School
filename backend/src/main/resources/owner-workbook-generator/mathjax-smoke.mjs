import MathJax from 'mathjax';

const mj = await MathJax.init({ loader: { load: ['input/tex', 'output/svg'] }, svg: { fontCache: 'local' } });
const cases = [
  String.raw`\\frac{x+2}{x-3}`,
  String.raw`(2x-3)^2`,
  String.raw`\\frac{x^2-9}{x-3}=x+3`,
  String.raw`\\sqrt{x+4}=3`,
];
for (const tex of cases) {
  const node = await mj.tex2svgPromise(tex, { display:false, em:16, ex:8, containerWidth:1280 });
  const svgNode = mj.startup.adaptor.tags(node, 'svg')[0];
  const svg = svgNode ? mj.startup.adaptor.serializeXML(svgNode) : '';
  if (!svg.includes('<svg') || svg.length < 200) throw new Error('MathJax SVG smoke test failed: ' + tex);
}
console.log('PASS MathJax SVG: fractions, powers, algebra and roots');
