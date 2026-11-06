/** New documents use {latex}. Legacy token arrays remain accepted. */
export type MathExpression = {latex:string; tokens?:never} | {tokens:string[]; latex?:never};
export type MathInput = MathExpression | string[];
export type RichText = {type:'richText';content:({text:string;math?:never}|{math:string|MathExpression; text?:never})[]};
export type Text = string | RichText;
export type Length = `${number}mm` | `${number}px` | `${number}em`;
export type FunctionDefinition =
  | {kind:'linear';m:number;b:number;label?:string}
  | {kind:'quadratic';a:number;b:number;c:number;label?:string}
  | {kind:'polynomial';coefficients:number[];label?:string}
  | {kind:'absolute'|'reciprocal'|'sqrt'|'exponential'|'logarithmic'|'log';a?:number;h?:number;k?:number;base?:number;label?:string}
  | {kind:'sine'|'cosine';amplitude:number;frequency:number;phase?:number;offset?:number;label?:string}
  | {kind:'constant';c?:number;value?:number;label?:string}
  | {kind:'rational';numerator:number[];denominator:number[];label?:string}
  | {kind:'piecewise';pieces:{domain:[number,number];includeStart?:boolean;includeEnd?:boolean;function:FunctionDefinition}[];label?:string};
export type Graph = {type:'graph';series:FunctionDefinition[];xRange?:[number,number];yRange?:[number,number];gridStep?:number;showAxisLabels?:boolean;showIntersections?:boolean;showIntersectionLabels?:boolean;label?:string;caption?:string};
export type Block =
  | RichText
  | {type:'text'|'explanation';text:Text}
  | {type:'math';expression:MathInput}
  | {type:'row'|'column'|'stack';children:Block[];gap?:Length}
  | {type:'grid';columns:1|2|3|4;children:Block[];gap?:Length}
  | {type:'split';columns?:[`${number}%`,`${number}%`];gap?:Length;left:Block[];right:Block[]}
  | {type:'taskCard';number?:string;title?:Text;subtitle?:Text;description?:Text;children:Block[]}
  | {type:'answerLines';lines?:number;spacing?:Length;label?:Text}
  | {type:'answerBox';height?:Length;label?:Text;text?:Text}
  | {type:'studentHeader';fields?:Text[]}
  | {type:'matching';title?:Text;left:Text[];right:Text[];matches?:[number,number][];showConnections?:boolean}
  | {type:'conclusionBox';title?:Text;text?:Text;lines?:number;children?:Block[]}
  | {type:'spacer';height?:Length}
  | {type:'divider'}
  | {type:'coordinateGrid';xRange?:[number,number];yRange?:[number,number];gridStep?:number;showAxisLabels?:boolean;label?:string}
  | {type:'intersectionResult';graph:Graph;label?:Text}
  | Graph
  | {type:'callout';label?:Text;text:Text}
  | {type:'example';label?:Text;text?:Text;expressions:MathInput[]}
  | {type:'facts';label?:Text;givenTitle?:Text;findTitle?:Text;given?:Text[];find?:Text[];notes?:Text[]}
  | {type:'exercise';number?:string;title?:Text;subtitle?:Text;description?:Text;explanation?:Text;rows?:{label:Text;math:MathInput;work?:boolean}[];writeLines?:number;size?:'relaxed'|'compact';variant?:'exit';selfRating?:string;extra?:{label:Text;math:MathInput}}
  | {type:'teacherNote';label?:Text;title?:Text;text?:Text;paragraphs?:Text[]}
  | {type:'schedule';label?:Text;title?:Text;rows:[Text,Text][]}
  | {type:'solutions';section?:Text;title?:Text;items:{label:Text;math?:MathInput;text?:Text;note?:Text;explanation?:Text;validation?:AlgebraCheck}[];footerNote?:Text}
  | {type:'geometry';figure:'triangle'|'rectangle'|'quadrilateral'|'circle'|'cube'|'cuboid';[key:string]:unknown}
  | {type:'strips';label?:Text;left:{numerator:number;denominator:number};right:{numerator:number;denominator:number};relation:string};
export type AlgebraCheck =
  | {kind:'equivalence';expression:string;answer:string;restrictions?:string[]}
  | {kind:'solution';equation:string;solution:Record<string,string|number>;restrictions?:string[]};
export interface LessonPage {title:Text;subtitle?:Text;time?:Text;blocks:Block[]}
export interface Lesson {
  schemaVersion?:2;
  meta:{school:string;subject:string;group:string;date:string;durationMinutes:number;[key:string]:unknown};
  workbookPages:LessonPage[];
  teacherPages:LessonPage[];
  qa?:{algebra?:AlgebraCheck[]};
}
