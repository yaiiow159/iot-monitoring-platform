import { useEffect, useState } from 'react';

/** 頂欄的即時時鐘。監控大屏掛在牆上，時間本身就是一個要一直看得到的讀數。 */
export function Clock() {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const id = window.setInterval(() => setNow(new Date()), 1000);
    return () => window.clearInterval(id);
  }, []);
  const pad = (n: number) => String(n).padStart(2, '0');
  return (
    <time className="clock mono" dateTime={now.toISOString()} title={now.toLocaleDateString('zh-TW')}>
      <span className="clock-date">
        {now.getFullYear()}.{pad(now.getMonth() + 1)}.{pad(now.getDate())}
      </span>
      <span className="clock-time">
        {pad(now.getHours())}
        <span className="clock-colon">:</span>
        {pad(now.getMinutes())}
        <span className="clock-colon">:</span>
        {pad(now.getSeconds())}
      </span>
    </time>
  );
}
