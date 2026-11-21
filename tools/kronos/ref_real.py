import sys, numpy as np
sys.path.insert(0,'tools/kronos'); import reference_check as R
res='src/main/resources/assets/financialmarket/models/kronos'
tok=R.load(res+'/kronos-tokenizer.safetensors'); mdl=R.load(res+'/kronos-mini.safetensors')
rows=[l.split(',') for l in open('.data/ashare/000001.csv').read().strip().splitlines()[1:]]
n=512; sub=rows[-n:]
x=np.array([[float(f[1]),float(f[3]),float(f[4]),float(f[2]),float(f[5]),float(f[6])] for f in sub],dtype=np.float32)
x=(x-x.mean(0))/(x.std(0)+1e-5); x=np.clip(x,-5,5)
stamp=np.zeros((n,5),dtype=np.int64)
for i in range(n):
    d=10000-n+i+1
    stamp[i]=[0,0,(d-1)%7,1+(d-1)%28,1+((d-1)//28)%12]
s1,s2=R.run_tokenizer(tok,x)
logits,ctx=R.run_model_s1(mdl,s1,s2,stamp)
np.savetxt('.verify/tabl_probe/ref512.txt',logits[-1],fmt='%.7g')
np.save('.verify/tabl_probe/ref512_ctx.npy',ctx[-1])
print('ref argmax',int(np.argmax(logits[-1])),'max',float(logits[-1].max()),'s1[0..3]',s1[:4].tolist())
