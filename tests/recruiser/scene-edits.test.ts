import 'fake-indexeddb/auto';
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {alignmentHistory,removeLayer,restoreLayer,restoreAlignment,sameAlignment} from '../../lib/recruiser/scene-edits';
import {openStore,identityPose,type Layer,type Revision,type Source} from '../../lib/recruiser/storage';
import {exportProject,importProject} from '../../lib/recruiser/archive';
import {EXPORT_CHANNEL,validHandoff} from '../../lib/recruiser/export-handoff';

test('alignment recovery follows ancestry, ignores visibility revisions and retains current metadata',()=>{
 const original:Layer={id:'layer',name:'a.glb',kind:'mesh',sourceIds:['source'],blobKey:'hash',pose:identityPose(),scale:2,visible:true,alignment:'unregistered',provenance:{original:true}};
 const flipped:Layer={...original,pose:{position:[3,4,5],quaternion:[1,0,0,0]},scale:3,alignment:'manual'};
 const current={...flipped,name:'renamed',visible:false,provenance:{new:true}};
 const revision=(id:string,parent:string|null,layer:Layer):Revision=>({id,parentRevisionId:parent,sceneId:'scene',worldFrameId:'world',unitStatus:'unknown',createdAt:'same timestamp',layers:[layer],vignetteIds:[],title:'test'});
 const history=alignmentHistory([revision('root',null,original),revision('flip','root',flipped),revision('unrelated','root',{...original,scale:99}),revision('head','flip',current)],'head','layer');
 assert.equal(sameAlignment({...original,pose:{...original.pose,quaternion:[0,0,0,.9999]}},original),true);
 assert.deepEqual(history.original,original);assert.deepEqual(history.previous,original);
 const reset=restoreAlignment(current,history.original!);
 assert.deepEqual(reset.pose,original.pose);assert.equal(reset.scale,2);assert.equal(reset.name,'renamed');assert.equal(reset.visible,false);assert.deepEqual(reset.provenance,{new:true});
 reset.pose.position[0]=42;assert.equal(original.pose.position[0],0);
});

test('remove, archive round-trip and restore retain historical source evidence and remove active items',async()=>{
 const store=await openStore(),sceneId=crypto.randomUUID(),blob=await store.putBlob(new Blob(['original bytes']));
 const source:Source={id:crypto.randomUUID(),sceneId,...blob,name:'capture.mp4',mime:'video/mp4',importedAt:'2026-09-11T00:00:00Z',captureTime:null,projection:'perspective',lensProfile:null,vignetteId:'vig'};
 await store.saveSource(source);
 const layer:Layer={id:'mesh',name:'scan.glb',kind:'mesh',blobKey:blob.blobKey,sourceIds:[source.id],pose:identityPose(),scale:1,visible:true,alignment:'manual',provenance:{worker:'test'}};
 const before:Revision={id:crypto.randomUUID(),sceneId,parentRevisionId:null,worldFrameId:'world',unitStatus:'unknown',createdAt:'2026-09-11T00:00:00Z',title:'removal',layers:[layer],vignetteIds:['vig']};
 await store.appendRevision(sceneId,null,before);
 const after={...before,id:crypto.randomUUID(),parentRevisionId:before.id,layers:removeLayer(before.layers,layer.id),removedSourceIds:[source.id],createdAt:'2026-09-11T00:01:00Z'};
 await store.appendRevision(sceneId,before.id,after);
 const restored=await store.getScene((await importProject(await exportProject(sceneId))).sceneId);
 assert.equal(restored.revision.layers.length,0);assert.deepEqual(restored.revision.removedSourceIds,[restored.sources[0].id]);assert.notEqual(restored.sources[0].id,source.id);
 const historical=restored.revisions.find(r=>r.layers.length)!;
 assert.deepEqual(historical.layers[0].sourceIds,[restored.sources[0].id]);assert.equal(await(await store.getBlob(restored.sources[0].blobKey)).text(),'original bytes');
 const undo={...restored.revision,id:crypto.randomUUID(),parentRevisionId:restored.head,layers:restoreLayer(restored.revision.layers,historical.layers[0],0),removedSourceIds:[]};
 await store.appendRevision(restored.id,restored.head,undo);
 const scene=await store.getScene(restored.id);assert.equal(scene.revision.layers.length,1);assert.equal(scene.revision.layers[0].visible,true);assert.equal(scene.revision.removedSourceIds?.length,0);
 assert.equal(scene.revisions.find(r=>r.id===restored.head)?.layers.length,0);
 assert.equal(restoreLayer(scene.revision.layers,historical.layers[0],0).length,1);
});

test('export handoff rejects wrong origin, window and session',()=>{
 const peer={} as Window,origin='https://recruiser.example',session='random';
 const event={source:peer,origin,data:{channel:EXPORT_CHANNEL,session,type:'ready'}} as MessageEvent;
 assert.equal(validHandoff(event,peer,origin,session),true);
 assert.equal(validHandoff({...event,origin:'https://attacker.example'} as MessageEvent,peer,origin,session),false);
 assert.equal(validHandoff({...event,source:{}} as MessageEvent,peer,origin,session),false);
 assert.equal(validHandoff(event,peer,origin,'different'),false);
 assert.equal(validHandoff(event,null,origin,session),false);
});
