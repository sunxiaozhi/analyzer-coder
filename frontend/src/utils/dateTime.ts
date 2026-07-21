const shanghaiFormatter=new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false});

export function formatShanghaiDateTime(value:string):string{
  const parts=Object.fromEntries(shanghaiFormatter.formatToParts(new Date(value)).map(part=>[part.type,part.value]));
  return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}:${parts.second}`;
}

export function formatRelativeTime(value:string):string{
  const seconds=Math.round((new Date(value).getTime()-Date.now())/1000),absolute=Math.abs(seconds);
  const formatter=new Intl.RelativeTimeFormat('zh-CN',{numeric:'auto'});
  if(absolute<60)return formatter.format(seconds,'second');
  if(absolute<3600)return formatter.format(Math.round(seconds/60),'minute');
  if(absolute<86400)return formatter.format(Math.round(seconds/3600),'hour');
  return formatter.format(Math.round(seconds/86400),'day');
}

export function shanghaiDateTimeTitle(value:string):string{return `${formatShanghaiDateTime(value)} Asia/Shanghai`;}
