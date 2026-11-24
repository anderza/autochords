const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const html=fs.readFileSync('app/src/main/assets/index.html','utf8');
const start=html.indexOf('function fft('),end=html.indexOf('let analysisQueued=',start);
assert.ok(start>=0&&end>start);
const a=vm.runInNewContext("const NOTES=['C','C#','D','D#','E','F','F#','G','G#','A','A#','B'];"+html.slice(start,end)+";({fft,pitchChroma,chordEmission,smoothChordFrames,ANALYZER_REV})");
console.log('ANALYZER_REV='+a.ANALYZER_REV);
const roots={C:48,D:50,E:52,F:53,G:55,A:57,B:59};
for(const seq of [['Am','F','C','G'],['C','G','Am','F'],['Dm','G','C'],['Em','Am','Dm','G']]){
test(seq.join('–'),()=>{
const sr=44100,n=16384,step=Math.floor(sr*.16),duration=4;
const pcm=new Float32Array(sr*duration*seq.length);
for(let i=0;i<pcm.length;i++){const chord=seq[Math.floor(i/(sr*duration))],root=roots[chord[0]],t=i/sr;for(const m of [root,root+(chord.endsWith('m')?3:4),root+7])pcm[i]+=.15*Math.sin(2*Math.PI*440*2**((m-69)/12)*t);}
const re=new Float32Array(n),im=new Float32Array(n),mag=new Float32Array(n/2),raw=[],levels=[],times=[];
for(let at=0;at+n<=pcm.length;at+=step){let rms=0;for(let i=0;i<n;i++){const x=pcm[at+i];rms+=x*x;re[i]=x*(.5-.5*Math.cos(2*Math.PI*i/(n-1)));im[i]=0;}a.fft(re,im);for(let i=0;i<mag.length;i++)mag[i]=Math.hypot(re[i],im[i]);raw.push(a.chordEmission(a.pitchChroma(mag,sr,n)));levels.push(Math.sqrt(rms/n));times.push((at+n/2)/sr);}
const norm=levels.slice().sort((a,b)=>a-b)[Math.floor(levels.length*.7)];
const {labels}=a.smoothChordFrames(raw,levels.map(x=>x/norm));let correct=0,total=0;
for(let i=0;i<labels.length;i++){const t=times[i],offset=t%duration;if(offset<.6||offset>duration-.6)continue;total++;if(labels[i]===seq[Math.floor(t/duration)])correct++;}
console.log(seq.join('–')+': '+correct+'/'+total);assert.ok(total>0&&correct/total>=.9);
});}
