/* Host test tool: enc|dec password header text -> mirrors the app's ENC1 packing (salt8|iv12|ct|tag16). */
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include "ribbit_crypto.h"
static const char *B="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
static void b64(const unsigned char*p,int n){for(int i=0;i<n;i+=3){unsigned v=p[i]<<16|(i+1<n?p[i+1]<<8:0)|(i+2<n?p[i+2]:0);
 putchar(B[v>>18&63]);putchar(B[v>>12&63]);putchar(i+1<n?B[v>>6&63]:'=');putchar(i+2<n?B[v&63]:'=');}}
static int unb64(const char*s,unsigned char*o){int n=0,bits=0;unsigned v=0;for(;*s&&*s!='=';s++){const char*q=strchr(B,*s);if(!q)continue;v=v<<6|(q-B);bits+=6;if(bits>=8){bits-=8;o[n++]=v>>bits&255;}}return n;}
int main(int c,char**v){ const char*pw=v[2],*hd=v[3],*tx=v[4]; unsigned char key[32],pk[512],pt[512];
 if(!strcmp(v[1],"enc")){ int n=strlen(tx); rc_random(pk,20); rc_pbkdf2_sha256((void*)pw,strlen(pw),pk,8,100000,key,32);
  rc_aes256gcm_encrypt(key,pk+8,(void*)hd,strlen(hd),(void*)tx,n,pk+20,pk+20+n); printf("ENC1:"); b64(pk,36+n); putchar('\n'); return 0;}
 int n=unb64(tx+5,pk); if(n<36) return 2; rc_pbkdf2_sha256((void*)pw,strlen(pw),pk,8,100000,key,32);
 if(rc_aes256gcm_decrypt(key,pk+8,(void*)hd,strlen(hd),pk+20,n-36,pk+n-16,pt)){puts("DECRYPT_FAIL");return 1;}
 pt[n-36]=0; printf("%s\n",pt); return 0;}
