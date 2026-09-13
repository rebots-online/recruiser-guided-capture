import test from 'node:test';
import assert from 'node:assert/strict';
import {prepareMediaPreview,inspectMediaProjection,inspectMedia} from '../../lib/recruiser/ingest';

test('INSP JPEG preview overrides absent or generic Android MIME without changing bytes',async()=>{
 for(const type of ['', 'application/octet-stream','application/x-insta360']){
  const original=new Blob([new Uint8Array([255,216,255,224,0,16,255,217]),'{"cameraType":"air","exportTime":0}'],{type});
  const result=await prepareMediaPreview(original,'IMG_0001.INSP');
  assert.equal(result.mime,'image/jpeg');assert.equal(result.kind,'image');
  assert.deepEqual(await result.blob.arrayBuffer(),await original.arrayBuffer());
  assert.equal(original.type,type);
 }
});

test('raw formats are previewable only with a recognized image or video signature',async()=>{
 const movie=new Blob([new Uint8Array([0,0,0,32]),'ftypisom']);
 assert.equal((await prepareMediaPreview(movie,'VID.insv')).kind,'video');
 for(const name of ['IMG.insp','VID.insv']){
  assert.equal((await prepareMediaPreview(new Blob(['unrecognized'],{type:'image/jpeg'}),name)).kind,'unsupported');
 }
 assert.equal((await prepareMediaPreview(new Blob([new Uint8Array([255,216])]),'truncated.insp')).kind,'unsupported');
});

test('image ISO containers are not misclassified as MP4 video',async()=>{
 for(const name of ['image.avif','image.heic','image.heif']){
  const preview=await prepareMediaPreview(new Blob([new Uint8Array([0,0,0,32]),'ftypavif']),name);
  assert.equal(preview.kind,'image');
 }
});

test('only explicit Air original metadata identifies dual fisheye; shape alone is insufficient',async()=>{
 const metadata=(value:unknown)=>new Blob(['jpeg-prefix',JSON.stringify(value),'\u0000\u0000']);
 assert.equal(await inspectMediaProjection(metadata({cameraType:'air',exportTime:0}),'image.insp'),'dual-fisheye');
 assert.equal(await inspectMediaProjection(metadata({extraData:{euler:{}},info:{cameraType:'air',exportTime:0},version:1}),'image.insp'),'dual-fisheye');
 for(const value of [{width:3008,height:1504},{cameraType:'air',exportTime:1},{cameraType:'other',exportTime:0},{cameraType:'air',exportTime:'0'}]){
  assert.equal(await inspectMediaProjection(metadata(value),'image.insp'),'unknown');
 }
 assert.equal(await inspectMediaProjection(new Blob(['{broken json}']),'image.insp'),'unknown');
 assert.equal(await inspectMediaProjection(metadata({cameraType:'air',exportTime:0}),'video.insv'),'unknown');
});

test('camera inspection is bounded and does not mutate an ordinary photo into a panorama',async()=>{
 const blob=new Blob(['{"cameraType":"air","exportTime":0}','x'.repeat(5000)]);
 assert.equal(await inspectMediaProjection(blob,'image.insp'),'unknown');
 const plain=new File(['{"width":3008,"height":1504}'],'photo.jpg');
 assert.equal((await inspectMedia(plain)).projection,'perspective');
});
