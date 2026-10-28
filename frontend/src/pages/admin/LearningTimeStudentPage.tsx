import { useEffect,useState } from 'react';
import { Link,useParams } from 'react-router-dom';
import { api } from '../../api/client';
import type { StudentTimeReport } from '../../api/types';
const minutes=(sec:number)=>sec?Math.round(sec/60)+' мин':'—';
export function LearningTimeStudentPage(){
 const {studentId=''}=useParams();const[days,setDays]=useState(30);const[report,setReport]=useState<StudentTimeReport|null>(null);const[error,setError]=useState('');
 useEffect(()=>{let live=true;setError('');setReport(null);
   api.learningTime.student(studentId,days).then(x=>{if(live)setReport(x);}).catch(x=>{if(live)setError(String(x));});
   return()=>{live=false;};
 },[studentId,days]);
 return <div className="stack">
   <Link to="/admin/learning-time">← Вся статистика</Link>
   <h1>{report?.summary.studentName||'Время обучения'}</h1>
   <label className="field">Период <select className="input" value={days} onChange={e=>setDays(Number(e.target.value))}><option value={7}>7 дней</option><option value={30}>30 дней</option><option value={90}>90 дней</option></select></label>
   {error&&<div className="banner banner--error">{error}</div>}
   {!report&&!error&&<p>Загружаем…</p>}
   {report&&<><div className="panel row" style={{justifyContent:'space-around',flexWrap:'wrap'}}>
       <div><strong>{minutes(report.summary.cardSeconds)}</strong><div className="muted">Карточки</div></div>
       <div><strong>{minutes(report.summary.homeworkSeconds)}</strong><div className="muted">Домашка</div></div>
       <div><strong>{report.summary.activeDays}</strong><div className="muted">Активных дней</div></div>
     </div><h2>История занятий</h2>
     {report.details.length===0?<p className="muted">Пока нет записанного времени.</p>:
     <div className="panel" style={{overflowX:'auto'}}><table style={{width:'100%',textAlign:'left'}}>
       <thead><tr><th>Тип</th><th>Задание</th><th>Время</th><th>Последняя активность</th><th>Завершено</th></tr></thead>
       <tbody>{report.details.map(d=><tr key={d.kind+d.referenceId}>
       <td>{d.kind==='CARDS'?'Карточки':'Домашка'}</td><td>{d.label}</td><td>{minutes(d.seconds)}</td><td>{d.lastActivityAt?new Date(d.lastActivityAt).toLocaleString('ru-RU'):'—'}</td><td>{d.completedAt?'Да':'Нет'}</td>
       </tr>)}</tbody>
     </table></div>}
   </>}
 </div>;
}
