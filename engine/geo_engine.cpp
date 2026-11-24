#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>

static const char* src;
static size_t pos;
static size_t len;
static size_t error_pos;

static void ws(){while(pos<len&&(src[pos]==' '||src[pos]=='\n'||src[pos]=='\r'||src[pos]=='\t'))++pos;}
static bool str(){
 if(pos>=len||src[pos]!='"')return false;++pos;
 while(pos<len){
  unsigned char c=(unsigned char)src[pos++];
  if(c=='"')return true;
  if(c<0x20)return false;
  if(c=='\\'){
   if(pos>=len)return false;
   char e=src[pos++];
   if(e=='u'){for(int i=0;i<4;i++){if(pos>=len)return false;char h=src[pos++];if(!((h>='0'&&h<='9')||(h>='a'&&h<='f')||(h>='A'&&h<='F')))return false;}}
   else if(e!='"'&&e!='\\'&&e!='/'&&e!='b'&&e!='f'&&e!='n'&&e!='r'&&e!='t')return false;
  }
 }
 return false;
}
static bool number(){
 size_t start=pos;
 if(pos<len&&src[pos]=='-')++pos;
 if(pos>=len)return false;
 if(src[pos]=='0')++pos;
 else {if(src[pos]<'1'||src[pos]>'9')return false;while(pos<len&&src[pos]>='0'&&src[pos]<='9')++pos;}
 if(pos<len&&src[pos]=='.'){++pos;size_t n=pos;while(pos<len&&src[pos]>='0'&&src[pos]<='9')++pos;if(n==pos)return false;}
 if(pos<len&&(src[pos]=='e'||src[pos]=='E')){++pos;if(pos<len&&(src[pos]=='+'||src[pos]=='-'))++pos;size_t n=pos;while(pos<len&&src[pos]>='0'&&src[pos]<='9')++pos;if(n==pos)return false;}
 return pos>start;
}
static bool value(int depth){
 if(depth>512)return false;ws();if(pos>=len)return false;char c=src[pos];
 if(c=='"')return str();
 if(c=='{' ){++pos;ws();if(pos<len&&src[pos]=='}'){++pos;return true;}while(pos<len){ws();if(!str())return false;ws();if(pos>=len||src[pos++]!=':')return false;if(!value(depth+1))return false;ws();if(pos<len&&src[pos]=='}'){++pos;return true;}if(pos>=len||src[pos++]!=',')return false;}return false;}
 if(c=='['){++pos;ws();if(pos<len&&src[pos]==']'){++pos;return true;}while(pos<len){if(!value(depth+1))return false;ws();if(pos<len&&src[pos]==']'){++pos;return true;}if(pos>=len||src[pos++]!=',')return false;}return false;}
 if(c=='-'||(c>='0'&&c<='9'))return number();
 if(len-pos>=4&&std::memcmp(src+pos,"true",4)==0){pos+=4;return true;}
 if(len-pos>=5&&std::memcmp(src+pos,"false",5)==0){pos+=5;return true;}
 if(len-pos>=4&&std::memcmp(src+pos,"null",4)==0){pos+=4;return true;}
 return false;
}
extern "C" {
int validate_json(const char* input,int length){
 src=input;len=length>0?(size_t)length:0;pos=0;error_pos=0;
 if(!src||!value(0)){error_pos=pos;return 0;}
 ws();if(pos!=len){error_pos=pos;return 0;}return 1;
}
int get_error_offset(){return (int)error_pos;}
}
