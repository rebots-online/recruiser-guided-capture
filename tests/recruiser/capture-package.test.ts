import {test} from 'node:test';
import assert from 'node:assert/strict';
import {strToU8,zipSync} from 'fflate';
import {readCapturePackage} from '../../lib/recruiser/capture-package';
import {captureFixture} from './capture-package-fixture';
import 'fake-indexeddb/auto';
import {openStore,type Source} from '../../lib/recruiser/storage';

test('capture package preserves original bytes, depth and exact timestamp above Number precision',async()=>{
 const fixture=captureFixture(),file=fixture.file(),capture=await readCapturePackage(file);
 assert.equal(capture.original,file);assert.equal(capture.frames[0].timestampNs,'9007199254740993');assert.equal(capture.manifest.clocks[1].offsetToSessionNs,null);assert.equal(capture.frames[0].depth?.stale,false);
 assert.deepEqual(new Uint8Array(await capture.assets.get('depth/frame-1.depth16')!.arrayBuffer()),fixture.entries['depth/frame-1.depth16']);
 assert.equal(await capture.assets.get('observations/frames.jsonl')!.text(),new TextDecoder().decode(fixture.entries['observations/frames.jsonl']));
 assert.equal(capture.manifest.reconstructions[0].path,'derived/world-1.ply');assert.match(await capture.assets.get('derived/world-1.ply')!.text(),/^ply\n/);
});
test('stored ZIP entries also round-trip',async()=>{assert.equal((await readCapturePackage(captureFixture().file(0))).frames.length,1)});
test('explicit unavailable depth and no IMU preserve an interrupted recording',async()=>{
 const f=captureFixture();f.manifest.capabilities.depth=false;f.manifest.capabilities.confidence=false;f.manifest.capabilities.imu=false;f.entries['observations/imu.jsonl']=new Uint8Array();delete f.entries['depth/frame-1.depth16'];delete f.entries['depth/frame-1.confidence8'];f.updateFrame({depth:null});
 const result=await readCapturePackage(f.file());assert.equal(result.frames[0].depth,null);assert.equal(result.imu.length,0);assert.equal(result.manifest.state,'interrupted');
});
test('empty capture observations are retained honestly',async()=>{const f=captureFixture();f.entries['observations/frames.jsonl']=new Uint8Array();f.entries['observations/quality.jsonl']=new Uint8Array();f.refresh();assert.equal((await readCapturePackage(f.file())).frames.length,0)});
test('undeclared archive files cannot enter storage',async()=>{const f=captureFixture();f.entries['unknown.bin']=new Uint8Array([1]);await assert.rejects(readCapturePackage(f.file()),/undeclared/)});
test('missing declared asset reports its path',async()=>{const f=captureFixture();delete f.entries['depth/frame-1.confidence8'];await assert.rejects(readCapturePackage(f.file()),/missing entries/)});
test('corrupted data with a valid ZIP checksum fails SHA-256',async()=>{const f=captureFixture();f.entries['depth/frame-1.confidence8']=new Uint8Array([2]);await assert.rejects(readCapturePackage(f.file()),/SHA-256 mismatch: depth\/frame-1.confidence8/)});
test('manifest version and malformed JSON reject precisely',async()=>{const f=captureFixture();Object.assign(f.manifest,{version:2});await assert.rejects(readCapturePackage(f.file()),/version/);const bad=new File([zipSync({'manifest.json':strToU8('{')}).slice().buffer],'bad.zip');await assert.rejects(readCapturePackage(bad),/invalid JSON/)});
test('unsafe archive paths and duplicate manifest assets are rejected',async()=>{for(const path of ['../outside','/absolute','depth\\bad','images/../bad','C:/bad']){const f=captureFixture();f.entries[path]=new Uint8Array([1]);f.refresh();await assert.rejects(readCapturePackage(f.file()),/Unsafe archive path/)}const f=captureFixture();f.manifest.assets.push(f.manifest.assets[0]);await assert.rejects(readCapturePackage(f.file()),/Duplicate manifest asset/)});
test('frame links require calibration, world frame and normalized pose',async()=>{
 const missing=captureFixture();missing.manifest.calibrations=[];await assert.rejects(readCapturePackage(missing.file()),/missing calibration/);
 const origin=captureFixture();origin.updateFrame({worldFrameId:'other'});await assert.rejects(readCapturePackage(origin.file()),/incompatible world frame/);
 const rotation=captureFixture();rotation.updateFrame({pose:{position:[0,0,0],quaternion:[0,0,0,2]}});await assert.rejects(readCapturePackage(rotation.file()),/normalized/);
 const lost=captureFixture();lost.updateFrame({tracking:'PAUSED'});await assert.rejects(readCapturePackage(lost.file()),/null pose/);
});
test('timestamps reject rounded numbers, exponent notation and overflow',async()=>{for(const timestamp of [9007199254740992,'9e15','9223372036854775808','-1','01']){const f=captureFixture();Object.assign(f.frame,{timestampNs:timestamp});f.updateFrame({});await assert.rejects(readCapturePackage(f.file()),/Frames/)} });
test('packed depth/confidence and depth time association must agree',async()=>{const f=captureFixture();f.entries['depth/frame-1.depth16']=new Uint8Array([1]);f.refresh();await assert.rejects(readCapturePackage(f.file()),/Packed depth size/);const stale=captureFixture();stale.updateFrame({depth:{...stale.frame.depth!,timestampNs:'9007199000000000'}});await assert.rejects(readCapturePackage(stale.file()),/stale status/)});
test('capability claims cannot contradict retained observations',async()=>{const f=captureFixture();f.manifest.capabilities.imu=false;await assert.rejects(readCapturePackage(f.file()),/IMU observations contradict/)});
test('unknown clock and invalid reconstructed PLY fail validation',async()=>{const clock=captureFixture();clock.manifest.clocks.pop();await assert.rejects(readCapturePackage(clock.file()),/IMU clock is missing/);const ply=captureFixture();ply.entries['derived/world-1.ply']=strToU8('not a PLY');ply.refresh();await assert.rejects(readCapturePackage(ply.file()),/not PLY/)});
test('central directory rejects duplicate names, expanded size bombs and mismatched local names',async()=>{
 async function changed(mutator:(bytes:Uint8Array,view:DataView,central:number)=>void){const bytes=new Uint8Array(await captureFixture().file(0).arrayBuffer()),view=new DataView(bytes.buffer),central=view.getUint32(bytes.length-6,true);mutator(bytes,view,central);return new File([bytes.buffer],'hostile.zip')}
 await assert.rejects(readCapturePackage(await changed((_,v,p)=>v.setUint32(p+24,0xffffffff,true))),/2 GiB uncompressed/);
 await assert.rejects(readCapturePackage(await changed((b,v,p)=>{const start=v.getUint32(p+42,true);b[start+30]=120})),/filename mismatch/);
 const duplicate=zipSync({'a.bin':new Uint8Array([1]),'b.bin':new Uint8Array([2])},{level:0}),duplicateView=new DataView(duplicate.buffer);let at=duplicateView.getUint32(duplicate.length-6,true);at+=46+duplicateView.getUint16(at+28,true)+duplicateView.getUint16(at+30,true)+duplicateView.getUint16(at+32,true);duplicate[at+46]=97;
 await assert.rejects(readCapturePackage(new File([duplicate.slice().buffer],'duplicate.zip')),/Duplicate ZIP entry name/);
});
test('incremental SHA matches Node crypto across empty, padding and stream boundaries',async()=>{for(const size of [0,55,56,63,64,65,65_535,65_536,65_537,1_048_583]){const f=captureFixture();f.entries['originals/segment-1.mp4']=new Uint8Array(size).map((_,i)=>i%251);f.refresh();const result=await readCapturePackage(f.file());assert.equal(result.assets.get('originals/segment-1.mp4')?.size,size)}});
test('JPEG calibration dimensions and image association are verified before preview',async()=>{
 const f=captureFixture();f.entries['images/frame-1.jpg']=new Uint8Array([255,216,255,192,0,11,8,0,1,0,1,1,1,17,0,255,217]);f.updateFrame({image:{path:'images/frame-1.jpg',timestampNs:f.frame.timestampNs,width:1,height:1}});assert.equal((await readCapturePackage(f.file())).frames[0].image?.width,1);
 f.entries['images/frame-1.jpg'][10]=2;f.refresh();await assert.rejects(readCapturePackage(f.file()),/JPEG dimensions differ/);
 f.entries['images/frame-1.jpg'][10]=1;f.updateFrame({image:{...f.frame.image!,timestampNs:'9007199264740993'}});await assert.rejects(readCapturePackage(f.file()),/not aligned/);
});
test('PLY vertex count and finite values are checked before opening a derived scene',async()=>{const count=captureFixture();count.entries['derived/world-1.ply']=strToU8(new TextDecoder().decode(count.entries['derived/world-1.ply']).replace('element vertex 3','element vertex 999999'));count.refresh();await assert.rejects(readCapturePackage(count.file()),/vertex count differs/);const nan=captureFixture();nan.entries['derived/world-1.ply']=strToU8(new TextDecoder().decode(nan.entries['derived/world-1.ply']).replace('0 0 -1','NaN 0 -1'));nan.refresh();await assert.rejects(readCapturePackage(nan.file()),/Malformed PLY vertex/)});
test('calibration and archive allocation bounds are enforced',async()=>{const f=captureFixture();f.manifest.calibrations[0].fx=0;await assert.rejects(readCapturePackage(f.file()),/fx/);class Oversized extends File{get size(){return 3*1024**3}}await assert.rejects(readCapturePackage(new Oversized([],'oversized.zip')),/2 GiB package limit/)});
test('stored capture session association and original observations survive reopening storage',async()=>{
 const f=captureFixture(),file=f.file(),capture=await readCapturePackage(file),store=await openStore(),sceneId='package-storage-test';
 await store.appendRevision(sceneId,null,{id:'package-storage-revision',sceneId,parentRevisionId:null,worldFrameId:'world-storage',unitStatus:'unknown',createdAt:f.manifest.createdAt,layers:[],vignetteIds:['capture-group'],title:'Capture package test'});
 const saved=await store.putBlob(file),source:Source={id:'package-source',sceneId,...saved,name:file.name,mime:file.type,importedAt:f.manifest.createdAt,captureTime:f.manifest.createdAt,projection:'unknown',lensProfile:null,vignetteId:'capture-group',capturePackage:{sessionId:capture.manifest.sessionId,state:capture.manifest.state,device:capture.manifest.provider.device,frameCount:capture.frames.length,depthCount:1}};
 await store.saveSource(source);const reopened=await openStore(),scene=await reopened.getScene(sceneId);assert.equal(scene.sources[0].capturePackage?.sessionId,capture.manifest.sessionId);const retained=await reopened.getBlob(scene.sources[0].blobKey);assert.deepEqual(new Uint8Array(await retained.arrayBuffer()),new Uint8Array(await file.arrayBuffer()));assert.equal((await readCapturePackage(new File([retained],source.name))).frames[0].timestampNs,f.frame.timestampNs);
});
