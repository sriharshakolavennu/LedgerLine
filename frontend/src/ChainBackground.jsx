import { useEffect, useRef } from 'react'

/** Drifting hash-blocks joined by glowing links, with pulses travelling along them. */
export default function ChainBackground() {
  const ref = useRef(null)
  useEffect(() => {
    const c = ref.current
    const ctx = c.getContext('2d')
    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    let w = 0, h = 0, raf = 0, nodes = [], pulses = []
    const D = 180

    const resize = () => {
      w = c.width = window.innerWidth
      h = c.height = window.innerHeight
      const n = Math.min(70, Math.floor((w * h) / 24000))
      nodes = Array.from({ length: n }, () => ({
        x: Math.random() * w, y: Math.random() * h,
        vx: (Math.random() - 0.5) * 0.28, vy: (Math.random() - 0.5) * 0.28,
        s: 7 + Math.random() * 9, hash: Math.random().toString(16).slice(2, 6),
      }))
    }

    const frame = () => {
      ctx.clearRect(0, 0, w, h)
      for (const n of nodes) {
        if (!reduce) { n.x += n.vx; n.y += n.vy }
        if (n.x < -20) n.x = w + 20; if (n.x > w + 20) n.x = -20
        if (n.y < -20) n.y = h + 20; if (n.y > h + 20) n.y = -20
      }
      for (let i = 0; i < nodes.length; i++) {
        for (let j = i + 1; j < nodes.length; j++) {
          const a = nodes[i], b = nodes[j]
          const d = Math.hypot(a.x - b.x, a.y - b.y)
          if (d < D) {
            ctx.strokeStyle = `rgba(16,245,160,${(1 - d / D) * 0.22})`
            ctx.lineWidth = 1
            ctx.beginPath(); ctx.moveTo(a.x, a.y); ctx.lineTo(b.x, b.y); ctx.stroke()
            if (!reduce && Math.random() < 0.0009) pulses.push({ a, b, t: 0 })
          }
        }
      }
      pulses = pulses.filter((p) => p.t < 1)
      for (const p of pulses) {
        p.t += 0.015
        const x = p.a.x + (p.b.x - p.a.x) * p.t, y = p.a.y + (p.b.y - p.a.y) * p.t
        ctx.fillStyle = 'rgba(59,130,246,.95)'
        ctx.shadowColor = '#3b82f6'; ctx.shadowBlur = 12
        ctx.beginPath(); ctx.arc(x, y, 2.4, 0, 7); ctx.fill(); ctx.shadowBlur = 0
      }
      ctx.font = '9px JetBrains Mono, monospace'
      for (const n of nodes) {
        ctx.strokeStyle = 'rgba(160,190,255,.28)'
        ctx.fillStyle = 'rgba(10,16,32,.7)'
        ctx.beginPath(); ctx.roundRect(n.x - n.s, n.y - n.s, n.s * 2, n.s * 2, 3); ctx.fill(); ctx.stroke()
        if (n.s > 12) { ctx.fillStyle = 'rgba(16,245,160,.55)'; ctx.fillText(n.hash, n.x - 8, n.y + 3) }
      }
      if (!reduce) raf = requestAnimationFrame(frame)
    }

    const onVis = () => {
      cancelAnimationFrame(raf)
      if (!document.hidden && !reduce) raf = requestAnimationFrame(frame)
    }
    resize(); frame()
    window.addEventListener('resize', resize)
    document.addEventListener('visibilitychange', onVis)
    return () => {
      cancelAnimationFrame(raf)
      window.removeEventListener('resize', resize)
      document.removeEventListener('visibilitychange', onVis)
    }
  }, [])
  return <canvas ref={ref} className="bg" aria-hidden="true" />
}
