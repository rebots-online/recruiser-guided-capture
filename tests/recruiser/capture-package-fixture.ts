import {createHash} from 'node:crypto';
import {strToU8,zipSync} from 'fflate';
import type {CaptureFrame,CaptureManifest} from '../../lib/recruiser/capture-package';

export function captureFixture(){
 const timestamp='9007199254740993';
 const frame:CaptureFrame={id:'frame-1',segmentId:'segment-1',worldFrameId:'world-1',timestampNs:timestamp,clockId:'arcore-camera',calibrationId:'calibration-1',tracking:'TRACKING',trackingFailure:null,pose:{position:[0,0,0],quaternion:[0,0,0,1]},image:null,depth:{path:'depth/frame-1.depth16',confidencePath:'depth/frame-1.confidence8',timestampNs:timestamp,width:1,height:1,fx:1,fy:1,cx:0,cy:0,stale:false}};
 const entries:Record<string,Uint8Array>={
  'originals/segment-1.mp4':new Uint8Array([0,0,0,20,102,116,121,112]),
  'observations/frames.jsonl':strToU8(JSON.stringify(frame)+'\n'),
  'observations/imu.jsonl':strToU8(JSON.stringify({sensor:'accelerometer',timestampNs:timestamp,clockId:'android-elapsed',values:[0,9.81,0],accuracy:3})+'\n'),
  'observations/events.jsonl':strToU8(JSON.stringify({timestampNs:timestamp,clockId:'arcore-camera',type:'pause-gap',detail:'Synthetic interrupted capture retained'})+'\n'),
  'observations/quality.jsonl':strToU8(JSON.stringify({frameId:frame.id,timestampNs:timestamp,laplacianVariance:null,darkFraction:null,brightFraction:null,overlapEstimate:null,findings:[{code:'image-unavailable',severity:'info',message:'Synthetic package contains no camera image.'}]})+'\n'),
  'depth/frame-1.depth16':new Uint8Array([232,3]),'depth/frame-1.confidence8':new Uint8Array([255]),
  'derived/world-1.ply':strToU8('ply\nformat ascii 1.0\nelement vertex 3\nproperty float x\nproperty float y\nproperty float z\nproperty uchar red\nproperty uchar green\nproperty uchar blue\nend_header\n0 0 -1 117 224 206\n0.2 0 -1 117 224 206\n0 0.2 -1 117 224 206\n'),
  'derived/world-1.json':strToU8(JSON.stringify({algorithm:'voxel-fusion-v1',inputFingerprint:'a'.repeat(64),worldFrameId:'world-1',voxelSizeMeters:.03,voxelCount:3,capacityReached:false,backend:'synthetic-fixture'})),
 };
 const manifest:CaptureManifest={format:'recruiser.capture',version:1,sessionId:'synthetic-session',createdAt:'2026-09-13T12:00:00Z',state:'interrupted',provider:{name:'arcore-android',version:'synthetic',device:'Synthetic capture fixture',androidApi:35},coordinateSystem:{handedness:'right',up:'+Y',forward:'-Z',units:'meters'},clocks:[{id:'arcore-camera',unit:'nanoseconds',offsetToSessionNs:'-'+timestamp,uncertaintyNs:'0',source:'Synthetic camera origin'},{id:'android-elapsed',unit:'nanoseconds',offsetToSessionNs:null,uncertaintyNs:null,source:'Synchronization unavailable'}],capabilities:{camera:true,pose:true,imu:true,depth:true,confidence:true,location:false},segments:[{id:'segment-1',path:'originals/segment-1.mp4',worldFrameId:'world-1',startTimestampNs:timestamp,endTimestampNs:timestamp,state:'interrupted',mime:'video/mp4',width:1,height:1}],calibrations:[{id:'calibration-1',cameraId:'rear',width:1,height:1,fx:1,fy:1,cx:0,cy:0,distortion:null,crop:null,rotationDegrees:0,lensId:null}],streams:{frames:'observations/frames.jsonl',imu:'observations/imu.jsonl',events:'observations/events.jsonl',quality:'observations/quality.jsonl'},assets:[],reconstructions:[{path:'derived/world-1.ply',metadataPath:'derived/world-1.json',worldFrameId:'world-1'}]};
 function refresh(){manifest.assets=Object.entries(entries).map(([path,bytes])=>({path,size:bytes.length,sha256:createHash('sha256').update(bytes).digest('hex'),mime:path.endsWith('.mp4')?'video/mp4':path.endsWith('.json')?'application/json':'application/octet-stream'}))}
 function updateFrame(change:Partial<CaptureFrame>){Object.assign(frame,change);entries['observations/frames.jsonl']=strToU8(JSON.stringify(frame)+'\n');refresh()}
 function file(level:0|6=6){return new File([zipSync({'manifest.json':strToU8(JSON.stringify(manifest)),...entries},{level}).slice().buffer],'synthetic.recruiser-capture.zip',{type:'application/zip'})}
 refresh();return{manifest,entries,frame,refresh,updateFrame,file};
}
