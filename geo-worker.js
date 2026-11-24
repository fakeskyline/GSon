"use strict";
let enginePromise=null;
function loadEngine(){
 if(enginePromise)return enginePromise;
 enginePromise=new Promise((resolve,reject)=>{
  if(typeof createGeoEngine!=="function"){reject(Error("C++ WebAssembly-модуль не найден. Скачай ZIP из GitHub Actions и распакуй его целиком."));return;}
  createGeoEngine({locateFile:p=>p}).then(resolve,reject);
 });
 return enginePromise;
}
self.onmessage=async e=>{
 const {action,payload}=e.data||{};
 try{
  if(action!=="parse-geojson")throw Error("Неизвестная операция C++-движка.");
  const text=payload.text;
  const Module=await loadEngine();
  const bytes=Module.lengthBytesUTF8(text);
  const ptr=Module._malloc(bytes+1);
  try{
   Module.stringToUTF8(text,ptr,bytes+1);
   if(!Module._validate_json(ptr,bytes)){
    const offset=Module._get_error_offset();
    throw Error("Некорректный JSON (C++ WebAssembly), позиция "+offset+".");
   }
  }finally{Module._free(ptr);}
  let o;try{o=JSON.parse(text.replace(/^\uFEFF/,""));}catch(err){throw Error("Не удалось разобрать GeoJSON: "+err.message);}
  let features=[];
  if(o.type==="FeatureCollection"&&Array.isArray(o.features))features=o.features;
  else if(o.type==="Feature")features=[o];
  else if(o.type&&o.coordinates!==undefined)features=[{type:"Feature",geometry:o,properties:{}}];
  else throw Error("Ожидался GeoJSON FeatureCollection, Feature или Geometry.");
  const allowed=new Set(["Point","LineString","Polygon","MultiPoint","MultiLineString","MultiPolygon","GeometryCollection"]);
  const check=g=>{if(!g||!allowed.has(g.type))throw Error("Неподдерживаемая геометрия: "+(g&&g.type));if(g.type==="GeometryCollection"){(g.geometries||[]).forEach(check);return;}if(!Array.isArray(g.coordinates))throw Error("Отсутствуют координаты геометрии.");};
  features.forEach((f,i)=>{if(!f||f.type!=="Feature")throw Error("Некорректный Feature №"+(i+1));if(f.geometry)check(f.geometry);});
  self.postMessage({result:{layers:[{name:payload.name.replace(/\.[^.]+$/,""),srs:4326,features}]}});
 }catch(err){self.postMessage({error:err&&err.message?err.message:String(err)});}
};
