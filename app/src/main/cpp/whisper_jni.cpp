#include <jni.h>
#include <android/log.h>
#include "whisper.h"
#include <cstdint>
#include <fstream>
#include <string>
#include <vector>
#include <algorithm>
#include <thread>
#include <cstdio>
#include <sstream>
#include <memory>
#include <malloc.h>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "LilacWhisper", __VA_ARGS__)
static uint32_t read_u32(std::ifstream& f) { uint32_t v=0; f.read(reinterpret_cast<char*>(&v),4); return v; }
static uint16_t read_u16(std::ifstream& f) { uint16_t v=0; f.read(reinterpret_cast<char*>(&v),2); return v; }
static std::string jstr(JNIEnv* env, jstring s) { const char* p=env->GetStringUTFChars(s,nullptr); std::string out=p?p:""; if(p) env->ReleaseStringUTFChars(s,p); return out; }
static std::string ts(int64_t ms) { if(ms<0)ms=0; int h=(int)(ms/3600000); ms%=3600000; int m=(int)(ms/60000); ms%=60000; int sec=(int)(ms/1000); int milli=(int)(ms%1000); char b[32]; snprintf(b,sizeof(b),"%02d:%02d:%02d,%03d",h,m,sec,milli); return std::string(b); }
extern "C" JNIEXPORT jstring JNICALL Java_com_lilac_anime_data_subtitle_whisper_WhisperNative_transcribeWav(JNIEnv* env, jobject, jstring modelJ, jstring wavJ) {
 const std::string modelPath=jstr(env,modelJ), wavPath=jstr(env,wavJ);
 whisper_context_params cp=whisper_context_default_params(); cp.use_gpu=false;
 std::unique_ptr<whisper_context, decltype(&whisper_free)> ctx(
     whisper_init_from_file_with_params(modelPath.c_str(),cp), &whisper_free);
 if(!ctx){ LOGE("MODEL_INIT_FAILED %s",modelPath.c_str()); return env->NewStringUTF(""); }
 std::ifstream f(wavPath,std::ios::binary);
 if(!f){ return env->NewStringUTF(""); }
 char riff[4], wave[4]; f.read(riff,4); read_u32(f); f.read(wave,4);
 if(std::string(riff,4)!="RIFF" || std::string(wave,4)!="WAVE"){ return env->NewStringUTF(""); }
 uint16_t format=1,channels=1,bits=16; uint32_t sampleRate=16000; bool found=false; uint32_t dataSize=0; std::streampos dataPos{};
 while(f && !found){ char id[4]; f.read(id,4); if(!f)break; uint32_t size=read_u32(f); std::string chunk(id,4);
  if(chunk=="fmt "){ format=read_u16(f); channels=read_u16(f); sampleRate=read_u32(f); read_u32(f); read_u16(f); bits=read_u16(f); if(size>16)f.seekg(size-16,std::ios::cur); }
  else if(chunk=="data"){ dataSize=size; dataPos=f.tellg(); found=true; }
  else f.seekg(size,std::ios::cur);
 }
 if(!found || format!=1 || bits!=16 || channels!=1 || sampleRate!=16000){ return env->NewStringUTF(""); }
 // Process 10 seconds per call to reduce peak native memory on mobile devices.
 constexpr size_t CHUNK_SAMPLES=16000u*10u; const size_t totalSamples=dataSize/2; size_t offset=0; int segmentNo=1; std::ostringstream out;
 whisper_full_params p=whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
 p.n_threads=1; p.language="ja"; p.translate=false; p.no_context=true; p.print_progress=false; p.print_realtime=false; p.print_timestamps=false; p.single_segment=false; p.audio_ctx=0;
 while(offset<totalSamples){
  const size_t count=std::min(CHUNK_SAMPLES,totalSamples-offset);
  {
   std::vector<int16_t> pcm(count);
   f.clear(); f.seekg(dataPos+static_cast<std::streamoff>(offset*2));
   f.read(reinterpret_cast<char*>(pcm.data()),static_cast<std::streamsize>(count*2));
   if((size_t)f.gcount()!=count*2) break;
   std::vector<float> samples(count);
   for(size_t i=0;i<count;++i) samples[i]=pcm[i]/32768.0f;
   const int rc=whisper_full(ctx.get(),p,samples.data(),(int)samples.size());
   if(rc!=0){ LOGE("WHISPER_FULL_FAILED rc=%d offset=%zu",rc,offset); break; }
   int n=whisper_full_n_segments(ctx.get());
   for(int i=0;i<n;++i){
    const int64_t base=(int64_t)offset*1000/16000;
    const int64_t a=base+whisper_full_get_segment_t0(ctx.get(),i)*10;
    const int64_t b=base+whisper_full_get_segment_t1(ctx.get(),i)*10;
    const char* t=whisper_full_get_segment_text(ctx.get(),i); if(!t) continue;
    std::string text(t); if(text.find_first_not_of(" \\t\\r\\n")==std::string::npos) continue;
    out<<segmentNo++<<"\\n"<<ts(a)<<" --> "<<ts(std::max(a+100,b))<<"\\n"<<text<<"\\n\\n";
   }
  } // Chunk buffers are destroyed before the next allocation.
  offset+=count;
  malloc_trim(0);
 }
 return env->NewStringUTF(out.str().c_str());
}
