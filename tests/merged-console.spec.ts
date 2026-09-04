import { execFileSync } from 'node:child_process'
import { mkdtempSync, readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'

import { JSDOM, VirtualConsole } from 'jsdom'
import { describe, expect, it } from 'vitest'

const root = resolve(__dirname, '..')
const outDir = mkdtempSync(join(tmpdir(), 'elma-merged-'))
const demoPath = join(outDir, 'index.html')
const productPath = resolve(root, 'output/product-demo/index.html')
const opsPath = resolve(root, 'output/database-guide/index.html')

function assemblePublic() {
  const script = resolve(root, 'output/merged-console/assemble.py')
  const args = [script, '--from-public', '--output', demoPath]
  const errors: unknown[] = []
  for (const bin of ['python', 'py']) {
    try {
      execFileSync(bin, args, { cwd: root, stdio: 'pipe' })
      return
    } catch (error) {
      errors.push(error)
    }
  }
  throw errors.at(-1)
}

assemblePublic()
const template = readFileSync(demoPath, 'utf8')
const product = readFileSync(productPath, 'utf8')
const ops = readFileSync(opsPath, 'utf8')

async function openDemo(url = 'file:///elma-merged-console.html') {
  const errors: string[] = []
  const virtualConsole = new VirtualConsole()
  virtualConsole.on('jsdomError', error => errors.push(String(error)))
  virtualConsole.on('error', error => errors.push(String(error)))
  const dom = new JSDOM(template, {
    runScripts: 'dangerously',
    pretendToBeVisual: true,
    url,
    virtualConsole,
  })
  await new Promise(resolvePromise => dom.window.setTimeout(resolvePromise, 40))
  return { dom, errors }
}

describe('merged product demo and ops dashboard', () => {
  it('keeps both reviewed pages as scoped panes in one offline file', () => {
    expect(template).toContain("connect-src 'none'")
    expect(template).not.toMatch(/<script\b[^>]*\bsrc\s*=/i)
    expect(template).not.toMatch(/<link\b[^>]*\brel\s*=\s*["']?stylesheet/i)
    expect(template).not.toMatch(/\bfetch\s*\(|\bXMLHttpRequest\b|\bWebSocket\b/i)
    expect(template).toContain('#elma-product')
    expect(template).toContain('#elma-ops')
    expect(template).toContain('一次只推荐一家餐厅')
    expect(template).toContain('湘味小馆')
    expect(template).toContain('推荐系统运营数据')
    expect(template).toContain('ELMA Gohan 数据库表关系图')
    expect(template).toContain('人工连接')
    expect(template).toContain('分歧 ≠ 提升')
    expect(template).toContain('不能把 64 个匿名标识称为真实用户')
    expect(product).toContain('一次只推荐一家餐厅')
    expect(ops).toContain('推荐系统运营数据')
  })

  it('switches panes without dropping product or dashboard interactions', async () => {
    const { dom, errors } = await openDemo()
    const { document } = dom.window

    expect(document.getElementById('elma-product')?.hidden).toBe(false)
    expect(document.getElementById('elma-ops')?.hidden).toBe(true)

    const steps = [...document.querySelectorAll<HTMLButtonElement>('#elma-product [data-step]')]
    expect(steps).toHaveLength(8)
    steps[5].click()
    expect(document.querySelectorAll('#elma-product [data-step][aria-pressed="true"]')).toHaveLength(1)
    expect(document.querySelector('#pipeline-detail')?.textContent).toContain('TasteProfile')

    document.querySelector<HTMLButtonElement>('[data-candidate="bbq"]')!.click()
    expect(document.querySelector('#score-panel')?.textContent).toContain('炭火烧肉')
    expect(document.querySelector('#score-panel')?.textContent).toContain('没有成为首选的原因')

    document.querySelector<HTMLButtonElement>('[data-algorithm="v05"]')!.click()
    expect(document.querySelector('#algorithm-card')?.textContent).toContain('仅 Shadow，不影响响应')
    expect(document.querySelector('#algorithm-card')?.textContent).toContain('RiskPosterior')

    document.querySelector<HTMLButtonElement>('[data-pane="ops"]')!.click()
    expect(document.getElementById('elma-product')?.hidden).toBe(true)
    expect(document.getElementById('elma-ops')?.hidden).toBe(false)
    expect(document.querySelector('#overview-view')?.hasAttribute('hidden')).toBe(false)
    expect(document.querySelectorAll('#trend-chart .trend-line').length).toBeGreaterThan(0)

    document.querySelector<HTMLButtonElement>('[data-view="locations"]')!.click()
    expect(document.querySelector('#locations-view')?.hasAttribute('hidden')).toBe(false)
    expect(document.querySelectorAll('.location-bubble').length).toBeGreaterThan(0)

    expect(errors).toEqual([])
    dom.window.close()
  })

  it('opens the operations pane from the hash without rewriting either page', async () => {
    const { dom, errors } = await openDemo('file:///elma-merged-console.html#ops')
    const { document } = dom.window
    expect(document.body.dataset.elmaPane).toBe('ops')
    expect(document.getElementById('elma-ops')?.hidden).toBe(false)
    expect(document.getElementById('elma-product')?.hidden).toBe(true)
    expect(document.querySelector('#elma-product')?.textContent).toContain('一次只推荐一家餐厅')
    expect(document.querySelector('#elma-ops')?.textContent).toContain('推荐系统运营数据')
    expect(errors).toEqual([])
    dom.window.close()
  })
})
