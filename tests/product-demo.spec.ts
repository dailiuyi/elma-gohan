import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { JSDOM, VirtualConsole } from 'jsdom'
import { describe, expect, it } from 'vitest'

const root = resolve(__dirname, '..')
const demoPath = resolve(root, 'output/product-demo/index.html')
const template = readFileSync(demoPath, 'utf8')

async function openDemo() {
  const errors: string[] = []
  const virtualConsole = new VirtualConsole()
  virtualConsole.on('jsdomError', error => errors.push(String(error)))
  virtualConsole.on('error', error => errors.push(String(error)))
  const dom = new JSDOM(template, {
    runScripts: 'dangerously',
    pretendToBeVisual: true,
    url: 'file:///elma-product-demo.html',
    virtualConsole,
  })
  await new Promise(resolvePromise => dom.window.setTimeout(resolvePromise, 20))
  return { dom, errors }
}

describe('product and algorithm demo', () => {
  it('is a self-contained public-safe offline artifact', () => {
    expect(template).toContain("connect-src 'none'")
    expect(template).not.toMatch(/<script\b[^>]*\bsrc\s*=/i)
    expect(template).not.toMatch(/<link\b[^>]*\brel\s*=\s*["']?stylesheet/i)
    expect(template).not.toMatch(/\bfetch\s*\(|\bXMLHttpRequest\b|\bWebSocket\b/i)
    expect(template).not.toMatch(/DB_PASSWORD|PGPASSWORD|DATABASE_URL|127\.0\.0\.1:\d+/)
    expect(template).toContain('示例候选已脱敏')
    expect(template).toContain('分歧 ≠ 提升')
    expect(template).toContain('不能把 64 个匿名标识称为真实用户')
  })

  it('switches pipeline steps and preserves one selected step', async () => {
    const { dom, errors } = await openDemo()
    const { document } = dom.window
    const steps = [...document.querySelectorAll<HTMLButtonElement>('[data-step]')]
    expect(steps).toHaveLength(8)
    steps[5].click()
    expect(document.querySelectorAll('[data-step][aria-pressed="true"]')).toHaveLength(1)
    expect(document.querySelector('#pipeline-detail')?.textContent).toContain('TasteProfile')
    expect(errors).toEqual([])
    dom.window.close()
  })

  it('explains candidate scores and serving versus shadow status', async () => {
    const { dom, errors } = await openDemo()
    const { document } = dom.window
    document.querySelector<HTMLButtonElement>('[data-candidate="bbq"]')!.click()
    expect(document.querySelector('#score-panel')?.textContent).toContain('炭火烧肉')
    expect(document.querySelector('#score-panel')?.textContent).toContain('没有成为首选的原因')
    expect(document.querySelectorAll('[data-candidate][aria-pressed="true"]')).toHaveLength(1)

    document.querySelector<HTMLButtonElement>('[data-algorithm="v05"]')!.click()
    expect(document.querySelector('#algorithm-card')?.textContent).toContain('仅 Shadow，不影响响应')
    expect(document.querySelector('#algorithm-card')?.textContent).toContain('RiskPosterior')
    expect(document.querySelectorAll('[data-algorithm][aria-pressed="true"]')).toHaveLength(1)
    expect(errors).toEqual([])
    dom.window.close()
  })
})
