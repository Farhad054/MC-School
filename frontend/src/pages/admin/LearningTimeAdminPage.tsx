import { useEffect,useMemo,useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../../api/client';
import type { StudentTimeSummary } from '../../api/types';

const minutes=(sec:number)=>sec?Math.round(sec/60)+' мин':'—';
export function LearningTimeAdminPage(){
  const [rows,setRows]=useState<StudentTimeSummary[]>([]);
  const [days,setDays]=useState(30);
  const [loading,setLoading]=useState(true);
  const [error,setError]=useState('');
  const [search,setSearch]=useState('');
  useEffect(()=>{let live=true;setLoading(true);setError('');
    api.learningTime.school(days).then(v=>{if(live)setRows(v);})
      .catch(e=>{if(live)setError(String(e));}).finally(()=>{if(live)setLoading(false);});
    return ()=>{live=false;};
  },[days]);
  const visible=useMemo(()=>rows.filter(r=>r.studentName.toLowerCase().includes(search.toLowerCase())),[rows,search]);
  const totalCards=visible.reduce((s,r)=>s+r.cardSeconds,0);
  const totalHomework=visible.reduce((s,r)=>s+r.homeworkSeconds,0);
  return <div className="stack">
    <h1>Время обучения учеников</h1>
    <p className="muted">Активные минуты на карточках и в PDF-домашке. Время бездействия и работы вне сайта не учитывается. Исторические занятия до установки таймера не включены.</p>
    <div className="row" style={{alignItems:'center',gap:12,flexWrap:'wrap'}}>
      <label className="field">Период <select className="input" value={days} onChange={e=>setDays(Number(e.target.value))}><option value={7}>7 дней</option><option value={30}>30 дней</option><option value={90}>90 дней</option></select></label>
      <label className="field">Ученик <input className="input" value={search} onChange={e=>setSearch(e.target.value)} placeholder="Поиск по имени"/></label>
    </div>
    <div className="panel row" style={{justifyContent:'space-around',flexWrap:'wrap'}}>
      <div><strong>{minutes(totalCards)}</strong><div className="muted">Карточки</div></div>
      <div><strong>{minutes(totalHomework)}</strong><div className="muted">Домашка</div></div>
      <div><strong>{visible.filter(r=>r.cardSeconds+r.homeworkSeconds>0).length}</strong><div className="muted">Активных учеников</div></div>
    </div>
    {loading&&<p>Загружаем…</p>}
    {error&&<div className="banner banner--error">{error}</div>}
    {!loading&&!error&&<div className="panel" style={{overflowX:'auto'}}><table style={{width:'100%',borderCollapse:'collapse',textAlign:'left'}}>
      <thead><tr><th>Ученик</th><th>Карточки</th><th>Домашка</th><th>Всего</th><th>Дней активности</th></tr></thead>
      <tbody>{visible.map(r=><tr key={r.studentId}><td><Link to={'/admin/learning-time/'+r.studentId}>{r.studentName}</Link></td><td>{minutes(r.cardSeconds)}</td><td>{minutes(r.homeworkSeconds)}</td><td>{minutes(r.cardSeconds+r.homeworkSeconds)}</td><td>{r.activeDays}</td></tr>)}</tbody>
    </table></div>}
  </div>;
}
