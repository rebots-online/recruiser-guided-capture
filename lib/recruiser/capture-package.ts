import {Inflate} from 'fflate';
import {z} from 'zod';

export const CAPTURE_LIMITS={bytes:2*1024**3,entries:100_000,records:2_000_000,jsonLineBytes:64*1024,manifestBytes:32*1024**2};
const text=new TextDecoder('utf-8',{fatal:true});
const id=z.string().min(1).max(160);
const ns=z.string().regex(/^(0|[1-9][0-9]{0,18})$/).refine(v=>/^(0|[1-9][0-9]{0,18})$/.test(v)&&BigInt(v)<=BigInt('9223372036854775807'),'Timestamp exceeds signed 64-bit nanoseconds');
const offset=z.string().regex(/^-?(0|[1-9][0-9]{0,18})$/).refine(v=>/^-?(0|[1-9][0-9]{0,18})$/.test(v)&&BigInt(v)>=-BigInt('9223372036854775808')&&BigInt(v)<=BigInt('9223372036854775807'));
const finite=z.number().finite(),positive=finite.positive(),dimension=z.number().int().positive().max(16384);
const path=z.string().max(1024).refine(v=>!!v&&!v.startsWith('/')&&!/[\\\x00-\x1f:]/.test(v)&&v.split('/').every(p=>p!==''&&p!=='.'&&p!=='..'),'Unsafe archive path');
const hash=z.string().regex(/^[0-9a-f]{64}$/);
const intrinsics={width:dimension,height:dimension,fx:positive,fy:positive,cx:finite,cy:finite};
const pose=z.object({position:z.tuple([finite,finite,finite]),quaternion:z.tuple([finite,finite,finite,finite]).refine(q=>Math.abs(Math.hypot(...q)-1)<.001,'Quaternion must be normalized')}).strict();
const calibration=z.object({id,cameraId:id,...intrinsics,distortion:z.null(),crop:z.null(),rotationDegrees:z.literal(0),lensId:z.null()}).strict();
const segment=z.object({id,path,worldFrameId:id,startTimestampNs:ns,endTimestampNs:ns.nullable(),state:z.enum(['finalized','interrupted']),mime:z.literal('video/mp4'),width:dimension,height:dimension}).strict();
const asset=z.object({path,size:z.number().int().nonnegative().max(CAPTURE_LIMITS.bytes),sha256:hash,mime:z.string().min(1).max(128)}).strict();
const manifestSchema=z.object({
 format:z.literal('recruiser.capture'),version:z.literal(1),sessionId:id,createdAt:z.string().datetime(),state:z.enum(['finalized','interrupted']),
 provider:z.object({name:z.literal('arcore-android'),version:id,device:id,androidApi:z.number().int().min(26).max(100)}).strict(),
 coordinateSystem:z.object({handedness:z.literal('right'),up:z.literal('+Y'),forward:z.literal('-Z'),units:z.literal('meters')}).strict(),
 clocks:z.array(z.object({id:z.enum(['arcore-camera','android-elapsed']),unit:z.literal('nanoseconds'),offsetToSessionNs:offset.nullable(),uncertaintyNs:ns.nullable(),source:z.string().min(1).max(1024)}).strict()).max(2),
 capabilities:z.object({camera:z.boolean(),pose:z.boolean(),imu:z.boolean(),depth:z.boolean(),confidence:z.boolean(),location:z.boolean()}).strict(),
 segments:z.array(segment).max(CAPTURE_LIMITS.entries),calibrations:z.array(calibration).max(CAPTURE_LIMITS.entries),
 streams:z.object({frames:z.literal('observations/frames.jsonl'),imu:z.literal('observations/imu.jsonl'),events:z.literal('observations/events.jsonl'),quality:z.literal('observations/quality.jsonl')}).strict(),
 assets:z.array(asset).max(CAPTURE_LIMITS.entries-1),reconstructions:z.array(z.object({path,metadataPath:path,worldFrameId:id}).strict()).max(CAPTURE_LIMITS.entries),
}).strict();
const frameSchema=z.object({id,segmentId:id,worldFrameId:id,timestampNs:ns,clockId:z.literal('arcore-camera'),calibrationId:id,tracking:z.enum(['TRACKING','PAUSED','STOPPED']),trackingFailure:z.string().max(1024).nullable(),pose:pose.nullable(),
 image:z.object({path,timestampNs:ns,width:dimension,height:dimension}).strict().nullable(),
 depth:z.object({path,confidencePath:path,timestampNs:ns,...intrinsics,stale:z.boolean()}).strict().nullable(),
}).strict();
const imuSchema=z.object({sensor:z.enum(['accelerometer','gyroscope']),timestampNs:ns,clockId:z.literal('android-elapsed'),values:z.tuple([finite,finite,finite]),accuracy:z.number().int().min(-1).max(3)}).strict();
const eventSchema=z.object({timestampNs:ns,clockId:id,type:id,detail:z.string().max(32768)}).strict();
const qualitySchema=z.object({frameId:id,timestampNs:ns,laplacianVariance:finite.nonnegative().nullable(),darkFraction:finite.min(0).max(1).nullable(),brightFraction:finite.min(0).max(1).nullable(),overlapEstimate:finite.min(0).max(1).nullable(),findings:z.array(z.object({code:id,severity:z.enum(['info','warning']),message:z.string().max(4096)}).strict()).max(100)}).strict();
const reconstructionSchema=z.object({algorithm:z.literal('voxel-fusion-v1'),inputFingerprint:hash,worldFrameId:id,voxelSizeMeters:positive,voxelCount:z.number().int().nonnegative().max(250000),capacityReached:z.boolean(),backend:z.string().min(1).max(1024)}).strict();
export type CaptureManifest=z.infer<typeof manifestSchema>;
export type CaptureFrame=z.infer<typeof frameSchema>;
export type CapturePackage={manifest:CaptureManifest;frames:CaptureFrame[];imu:z.infer<typeof imuSchema>[];events:z.infer<typeof eventSchema>[];quality:z.infer<typeof qualitySchema>[];assets:Map<string,Blob>;original:File};
type Entry={name:string;size:number;compressed:number;method:number;flags:number;offset:number;dataOffset:number};
function requireThat(value:unknown,message:string):asserts value {if(!value)throw Error(message)}
function parseJson<T>(value:string,schema:z.ZodType<T>,label:string):T {
 let json:unknown;try{json=JSON.parse(value)}catch{throw Error(label+': invalid JSON')}
 const parsed=schema.safeParse(json);if(!parsed.success){const issue=parsed.error.issues[0];throw Error(label+': '+issue.path.join('.')+' '+issue.message)}return parsed.data;
}
function unique(values:string[],label:string){requireThat(new Set(values).size===values.length,'Duplicate '+label)}
const abs=(n:bigint)=>n<BigInt('0')?-n:n;

// Inspect the central directory before allocating output. Unlike unzip-to-object,
// this preserves duplicate names and declared sizes so neither can hide attacks.
async function directory(file:File):Promise<Entry[]> {
 requireThat(file.size>=22,'Capture archive is empty or truncated');
 requireThat(file.size<=CAPTURE_LIMITS.bytes+64*1024**2,'Capture archive exceeds the 2 GiB package limit');
 const tailStart=Math.max(0,file.size-65557),tail=new Uint8Array(await file.slice(tailStart).arrayBuffer()),view=new DataView(tail.buffer);
 let end=-1;for(let i=tail.length-22;i>=0;i--)if(view.getUint32(i,true)===0x06054b50&&i+22+view.getUint16(i+20,true)===tail.length){end=i;break}
 requireThat(end>=0,'Capture archive has no valid ZIP directory');
 requireThat(view.getUint16(end+4,true)===0&&view.getUint16(end+6,true)===0,'Split ZIP archives are unsupported');
 let count=view.getUint16(end+10,true),length=view.getUint32(end+12,true),start=view.getUint32(end+16,true);
 if(count===65535){
  requireThat(end>=20&&view.getUint32(end-20,true)===0x07064b50,'Missing ZIP64 directory');
  const at=view.getBigUint64(end-12,true);requireThat(at<BigInt(file.size-56),'Invalid ZIP64 directory offset');
  const zip64=new DataView(await file.slice(Number(at),Number(at)+56).arrayBuffer());
  requireThat(zip64.getUint32(0,true)===0x06064b50&&zip64.getUint32(16,true)===0&&zip64.getUint32(20,true)===0,'Invalid ZIP64 directory');
  const n=zip64.getBigUint64(32,true),s=zip64.getBigUint64(40,true),o=zip64.getBigUint64(48,true);
  requireThat(n<=BigInt(CAPTURE_LIMITS.entries)&&s<=BigInt('64')*BigInt('1024')**BigInt('2')&&o<=BigInt(file.size),'ZIP64 package limits exceeded');count=Number(n);length=Number(s);start=Number(o);
 }else requireThat(view.getUint16(end+8,true)===count,'Inconsistent ZIP entry count');
 requireThat(count>0&&count<=CAPTURE_LIMITS.entries,'Capture archive entry limit exceeded');
 requireThat(length<=64*1024**2&&start+length<=tailStart+end,'Invalid or oversized ZIP directory');
 const bytes=new Uint8Array(await file.slice(start,start+length).arrayBuffer()),central=new DataView(bytes.buffer),entries:Entry[]=[];let p=0,total=0;
 for(let i=0;i<count;i++){
  requireThat(p+46<=bytes.length&&central.getUint32(p,true)===0x02014b50,'Truncated ZIP entry');
  const flags=central.getUint16(p+8,true),method=central.getUint16(p+10,true),compressed=central.getUint32(p+20,true),size=central.getUint32(p+24,true),names=central.getUint16(p+28,true),extra=central.getUint16(p+30,true),comment=central.getUint16(p+32,true),at=central.getUint32(p+42,true);
  requireThat(p+46+names+extra+comment<=bytes.length,'Truncated ZIP filename');
  const name=text.decode(bytes.subarray(p+46,p+46+names));requireThat(path.safeParse(name).success,'Unsafe archive path: '+name);
  requireThat((flags&~0x080e)===0&&(method===0||method===8),'Unsupported ZIP compression or encryption: '+name);
  requireThat(central.getUint16(p+34,true)===0,'Split ZIP entry is unsupported');
  total+=size;requireThat(total<=CAPTURE_LIMITS.bytes,'Capture archive exceeds 2 GiB uncompressed');
  entries.push({name,size,compressed,method,flags,offset:at,dataOffset:0});p+=46+names+extra+comment;
 }
 requireThat(p===bytes.length,'Inconsistent ZIP directory size');unique(entries.map(e=>e.name),'ZIP entry name');
 let previousEnd=0;
 for(const entry of [...entries].sort((a,b)=>a.offset-b.offset)){
  requireThat(entry.offset>=previousEnd&&entry.offset+30<=start,'Overlapping or invalid ZIP entry');
  const local=new DataView(await file.slice(entry.offset,entry.offset+30).arrayBuffer());
  requireThat(local.getUint32(0,true)===0x04034b50&&local.getUint16(6,true)===entry.flags&&local.getUint16(8,true)===entry.method,'ZIP local header mismatch');
  const names=local.getUint16(26,true),extra=local.getUint16(28,true);entry.dataOffset=entry.offset+30+names+extra;
  requireThat(entry.dataOffset+entry.compressed<=start,'ZIP data extends beyond archive');
  requireThat(text.decode(await file.slice(entry.offset+30,entry.offset+30+names).arrayBuffer())===entry.name,'ZIP filename mismatch');
  if(!(entry.flags&8))requireThat(local.getUint32(18,true)===entry.compressed&&local.getUint32(22,true)===entry.size,'ZIP local size mismatch');
  if(entry.method===0)requireThat(entry.size===entry.compressed,'Invalid stored ZIP entry size');
  previousEnd=entry.dataOffset+entry.compressed;
 }
 return entries;
}
async function extract(file:File,entry:Entry,mime:string):Promise<Blob>{
 if(entry.method===0)return file.slice(entry.dataOffset,entry.dataOffset+entry.size,mime);
 const chunks:BlobPart[]=[];let size=0;
 const inflate=new Inflate(chunk=>{size+=chunk.length;requireThat(size<=entry.size,'ZIP expanded size exceeds declaration: '+entry.name);chunks.push(chunk.slice().buffer)});
 try{for(let p=0;p<entry.compressed;p+=32768)inflate.push(new Uint8Array(await file.slice(entry.dataOffset+p,entry.dataOffset+Math.min(p+32768,entry.compressed)).arrayBuffer()),p+32768>=entry.compressed);if(!entry.compressed)inflate.push(new Uint8Array(),true)}catch(e){throw Error('Unable to decode '+entry.name+': '+(e as Error).message)}
 requireThat(size===entry.size,'ZIP expanded size mismatch: '+entry.name);return new Blob(chunks,{type:mime});
}
async function records<T>(blob:Blob,schema:z.ZodType<T>,label:string):Promise<T[]> {
 const reader=blob.stream().getReader(),decoder=new TextDecoder('utf-8',{fatal:true}),out:T[]=[];let pending='',line=0;
 function append(value:string){line++;requireThat(value.length<=CAPTURE_LIMITS.jsonLineBytes,label+': record is too large');if(!value.trim())return;requireThat(out.length<CAPTURE_LIMITS.records,label+': record limit exceeded');out.push(parseJson(value,schema,label+' line '+line))}
 try{for(;;){const {done,value}=await reader.read();pending+=decoder.decode(value,{stream:!done});let at:number;while((at=pending.indexOf('\n'))>=0){append(pending.slice(0,at));pending=pending.slice(at+1)}requireThat(pending.length<=CAPTURE_LIMITS.jsonLineBytes,label+': record is too large');if(done)break}if(pending.trim())append(pending)}finally{await reader.cancel();reader.releaseLock()}return out;
}

async function validateImage(blob:Blob,width:number,height:number){
 const bytes=new Uint8Array(await blob.slice(0,256*1024).arrayBuffer()),view=new DataView(bytes.buffer);
 requireThat(bytes.length>=4&&bytes[0]===255&&bytes[1]===216,'Retained image is not JPEG');let p=2;
 while(p+4<=bytes.length){requireThat(bytes[p++]===255,'Invalid JPEG marker');while(bytes[p]===255)p++;const marker=bytes[p++];if(marker===218||marker===217)break;if(marker===1||(marker>=208&&marker<=215))continue;requireThat(p+2<=bytes.length,'Truncated JPEG header');const length=view.getUint16(p);requireThat(length>=2&&p+length<=bytes.length,'JPEG header exceeds bounded inspection');if([192,193,194,195,197,198,199,201,202,203,205,206,207].includes(marker)){requireThat(length>=8&&view.getUint16(p+3)===height&&view.getUint16(p+5)===width,'JPEG dimensions differ from frame calibration');return}p+=length}
 throw Error('JPEG dimensions are missing from the bounded header');
}

async function validatePly(blob:Blob,expectedCount:number){
 const reader=blob.stream().getReader(),decoder=new TextDecoder('utf-8',{fatal:true});let pending='',header=true,headerBytes=0,count=-1,seen=0,format=false;const properties:string[]=[];
 function line(value:string){value=value.trim();if(header){headerBytes+=value.length+1;requireThat(headerBytes<=8192,'PLY header exceeds limit');if(value==='end_header'){requireThat(format&&count===expectedCount,'PLY vertex count differs from reconstruction metadata');requireThat(properties.join(',')==='x,y,z,red,green,blue','Unsupported coloured PLY properties');header=false}else if(value==='format ascii 1.0')format=true;else if(value.startsWith('element vertex ')){requireThat(count===-1,'Duplicate PLY vertex element');const raw=value.slice(15);requireThat(/^(0|[1-9][0-9]*)$/.test(raw),'Invalid PLY vertex count');count=Number(raw)}else if(value.startsWith('element '))throw Error('Unexpected PLY element');else if(value.startsWith('property ')){const fields=value.split(/\s+/);requireThat(fields.length===3,'Unsupported PLY property');properties.push(fields[2])}}else if(value){seen++;requireThat(seen<=expectedCount,'PLY has more vertices than declared');const fields=value.split(/\s+/);requireThat(fields.length===6&&fields.every(v=>/^[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:e[-+]?\d+)?$/i.test(v)),'Malformed PLY vertex');const values=fields.map(Number);requireThat(values.every(Number.isFinite)&&values.slice(3).every(v=>Number.isInteger(v)&&v>=0&&v<=255),'Invalid PLY coordinates or colour')}}
 try{for(;;){const{done,value}=await reader.read();pending+=decoder.decode(value,{stream:!done});let at:number;while((at=pending.indexOf('\n'))>=0){line(pending.slice(0,at));pending=pending.slice(at+1)}requireThat(pending.length<=8192,'PLY line exceeds limit');if(done)break}if(pending.trim())line(pending);requireThat(!header&&seen===expectedCount,'PLY is truncated or has missing vertices')}finally{await reader.cancel();reader.releaseLock()}
}

export async function readCapturePackage(file:File):Promise<CapturePackage>{
 const entries=await directory(file),byName=new Map(entries.map(e=>[e.name,e])),manifestEntry=byName.get('manifest.json');
 requireThat(manifestEntry,'Capture archive is missing manifest.json');requireThat(manifestEntry.size<=CAPTURE_LIMITS.manifestBytes,'Capture manifest is too large');
 const manifest=parseJson(await(await extract(file,manifestEntry,'application/json')).text(),manifestSchema,'Capture manifest');
 unique(manifest.assets.map(a=>a.path),'manifest asset');unique(manifest.segments.map(s=>s.id),'segment ID');unique(manifest.calibrations.map(c=>c.id),'calibration ID');unique(manifest.clocks.map(c=>c.id),'clock ID');
 requireThat(entries.length===manifest.assets.length+1,'Capture archive contains undeclared or missing entries');
 const assets=new Map<string,Blob>();
 for(const asset of manifest.assets){const entry=byName.get(asset.path);requireThat(asset.path!=='manifest.json'&&entry,'Missing declared asset: '+asset.path);requireThat(entry.size===asset.size,'Asset size mismatch: '+asset.path);const blob=await extract(file,entry,asset.mime),actual=await sha256(blob);requireThat(actual===asset.sha256,'SHA-256 mismatch: '+asset.path);assets.set(asset.path,blob)}
 const need=(name:string)=>{const blob=assets.get(name);requireThat(blob,'Missing observation asset: '+name);return blob};
 const frames=await records(need(manifest.streams.frames),frameSchema,'Frames'),imu=await records(need(manifest.streams.imu),imuSchema,'IMU'),events=await records(need(manifest.streams.events),eventSchema,'Events'),quality=await records(need(manifest.streams.quality),qualitySchema,'Quality');
 const clocks=new Set<string>(manifest.clocks.map(c=>c.id)),segments=new Map(manifest.segments.map(s=>[s.id,s])),calibrations=new Map(manifest.calibrations.map(c=>[c.id,c]));
 unique(frames.map(f=>f.id),'frame ID');const frameIds=new Map(frames.map(f=>[f.id,f]));
 for(const segment of manifest.segments){requireThat(segment.path==='originals/'+segment.id+'.mp4','Invalid segment path');need(segment.path);requireThat(segment.endTimestampNs===null||BigInt(segment.endTimestampNs)>=BigInt(segment.startTimestampNs),'Segment ends before it starts')}
 for(const frame of frames){
  const s=segments.get(frame.segmentId),c=calibrations.get(frame.calibrationId);requireThat(s&&s.worldFrameId===frame.worldFrameId,'Frame references missing segment or incompatible world frame: '+frame.id);requireThat(c,'Frame references missing calibration: '+frame.id);requireThat(clocks.has(frame.clockId),'Frame clock is missing');
  requireThat(frame.tracking==='TRACKING'||frame.pose===null,'Untracked frame must have a null pose: '+frame.id);
  requireThat(BigInt(frame.timestampNs)>=BigInt(s.startTimestampNs)&&(s.endTimestampNs===null||BigInt(frame.timestampNs)<=BigInt(s.endTimestampNs)),'Frame timestamp is outside its segment');
  if(frame.image){const image=frame.image;requireThat(image.path==='images/'+frame.id+'.jpg','Invalid image path');requireThat(image.width===c.width&&image.height===c.height,'Image dimensions differ from calibration');await validateImage(need(image.path),image.width,image.height);requireThat(abs(BigInt(image.timestampNs)-BigInt(frame.timestampNs))<=BigInt('2000000'),'Image timestamp is not aligned to frame')}
  if(frame.depth){const depth=frame.depth;requireThat(depth.path==='depth/'+frame.id+'.depth16'&&depth.confidencePath==='depth/'+frame.id+'.confidence8','Invalid depth/confidence path');requireThat(need(depth.path).size===depth.width*depth.height*2,'Packed depth size mismatch');requireThat(need(depth.confidencePath).size===depth.width*depth.height,'Packed confidence size mismatch');requireThat(depth.stale===(abs(BigInt(depth.timestampNs)-BigInt(frame.timestampNs))>BigInt('100000000')),'Depth stale status differs from timestamps')}
 }
 for(const record of imu)requireThat(clocks.has(record.clockId),'IMU clock is missing');
 for(const record of events)requireThat(clocks.has(record.clockId),'Event clock is missing: '+record.clockId);
 for(const record of quality){const frame=frameIds.get(record.frameId);requireThat(frame&&record.timestampNs===frame.timestampNs,'Quality record references missing frame or timestamp')}
 requireThat(manifest.capabilities.camera||!frames.some(f=>f.image),'Camera observations contradict capabilities');requireThat(manifest.capabilities.pose||!frames.some(f=>f.pose),'Pose observations contradict capabilities');requireThat(manifest.capabilities.imu||!imu.length,'IMU observations contradict capabilities');requireThat((manifest.capabilities.depth&&manifest.capabilities.confidence)||!frames.some(f=>f.depth),'Depth observations contradict capabilities');
 for(const reconstruction of manifest.reconstructions){
  requireThat(reconstruction.path.endsWith('.ply'),'Reconstruction must reference PLY');const ply=need(reconstruction.path);requireThat(text.decode(await ply.slice(0,4).arrayBuffer())==='ply\n'||text.decode(await ply.slice(0,5).arrayBuffer())==='ply\r\n','Reconstructed scene is not PLY');
  const metadata=need(reconstruction.metadataPath);requireThat(metadata.size<=65536,'Reconstruction metadata is too large');const parsed=parseJson(await metadata.text(),reconstructionSchema,'Reconstruction metadata');requireThat(parsed.worldFrameId===reconstruction.worldFrameId&&manifest.segments.some(s=>s.worldFrameId===parsed.worldFrameId),'Reconstruction world frame is missing or inconsistent');await validatePly(ply,parsed.voxelCount);
 }
 return {manifest,frames,imu,events,quality,assets,original:file};
}

// Incremental SHA-256 keeps integrity validation bounded even for large MP4s.
async function sha256(blob:Blob):Promise<string>{
 const k=new Uint32Array([0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2]);
 const h=new Uint32Array([0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19]),w=new Uint32Array(64),rotate=(v:number,n:number)=>(v>>>n)|(v<<(32-n));
 function block(bytes:Uint8Array,p:number){const v=new DataView(bytes.buffer,bytes.byteOffset+p,64);for(let i=0;i<16;i++)w[i]=v.getUint32(i*4);for(let i=16;i<64;i++){const a=w[i-15],b=w[i-2];w[i]=w[i-16]+(rotate(a,7)^rotate(a,18)^(a>>>3))+w[i-7]+(rotate(b,17)^rotate(b,19)^(b>>>10))}let[a,b,c,d,e,f,g,j]=h;for(let i=0;i<64;i++){const t=(j+(rotate(e,6)^rotate(e,11)^rotate(e,25))+((e&f)^(~e&g))+k[i]+w[i])>>>0;const u=((rotate(a,2)^rotate(a,13)^rotate(a,22))+((a&b)^(a&c)^(b&c)))>>>0;j=g;g=f;f=e;e=(d+t)>>>0;d=c;c=b;b=a;a=(t+u)>>>0}for(const[i,v]of[a,b,c,d,e,f,g,j].entries())h[i]=(h[i]+v)>>>0}
 let rest=new Uint8Array();const reader=blob.stream().getReader();try{for(;;){const {done,value}=await reader.read();if(done)break;const bytes=new Uint8Array(rest.length+value.length);bytes.set(rest);bytes.set(value,rest.length);let p=0;for(;p+64<=bytes.length;p+=64)block(bytes,p);rest=bytes.slice(p)}}finally{reader.releaseLock()}
 const final=new Uint8Array(rest.length<56?64:128);final.set(rest);final[rest.length]=128;new DataView(final.buffer).setBigUint64(final.length-8,BigInt(blob.size)*BigInt('8'));for(let p=0;p<final.length;p+=64)block(final,p);return Array.from(h,x=>x.toString(16).padStart(8,'0')).join('');
}
