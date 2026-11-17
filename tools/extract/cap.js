// usage: node cap.js jobs.json   job: {swf, out, scale, delay, count, interval}
const { chromium } = require('playwright'); const fs=require('fs');
(async()=>{
 const jobs=JSON.parse(fs.readFileSync(process.argv[2]));
 const b=await chromium.launch({args: process.env.NOGPU? ['--disable-gpu','--disable-gpu-compositing'] : ['--use-angle=swiftshader','--enable-unsafe-swiftshader']});
 const conc=parseInt(process.env.CONC||'4'); let idx=0;
 async function worker(){
  while(idx<jobs.length){ const j=jobs[idx++]; const S=j.scale||2;
   const pg=await b.newPage({viewport:{width:Math.round(550*S),height:Math.round(413*S)}});
   await pg.goto(`http://localhost:8765/cap.html?s=${S}&swf=${encodeURIComponent(j.swf)}&r=${j.renderer||'webgl'}`);
   try{ await pg.waitForFunction(()=>window.ready>0,{timeout:60000,polling:20}); }catch(e){ console.log('TIMEOUT',j.out); await pg.close(); continue; }
   await pg.waitForTimeout(j.delay??300);
   if(j.hover){ await pg.mouse.move(j.hover[0]*S,j.hover[1]*S); await pg.waitForTimeout(250); }
   const tStart=Date.now();
   const n=j.count||1; const rec=[];
   for(let i=0;i<n;i++){
     const t0=Date.now();
     const tb=Date.now()-tStart;
     await pg.screenshot({path: n>1? j.out.replace('.png',`_${String(i).padStart(3,'0')}.png`):j.out, omitBackground:true});
     if(j.record) rec.push([i,tb,Date.now()-tStart]);
     if(i<n-1){ const w=tStart+(i+1)*(j.interval||100)-Date.now(); if(w>0) await pg.waitForTimeout(w); }
   }
   if(j.record) fs.writeFileSync(j.out.replace('.png','_frames.json'),JSON.stringify(rec));
   await pg.close(); console.log('ok',j.out);
  }}
 await Promise.all(Array.from({length:conc},worker));
 await b.close();
})();
