import { render, screen } from '@testing-library/react'
import { beforeEach, expect, test, vi } from 'vitest'
import { EvidencePicker } from './EvidencePicker'

beforeEach(() => vi.restoreAllMocks())

test('回显已保存证据：定位到知识库/文档/版本并勾选分块', async () => {
  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    void init
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    if (url === '/api/knowledge-bases') {
      return json([{ id: 'kb-1', name: 'springai', description: null, slug: 'springai' }])
    }
    if (url === '/api/knowledge-bases/kb-1/documents') {
      return json([{ id: 'doc-1', filename: 'Java 8实战.pdf', contentType: 'application/pdf', sizeBytes: 1 }])
    }
    if (url === '/api/documents/doc-1/versions') {
      return json([{ id: 'dv-1', versionNo: 2, status: 'READY', chunkCount: 2, errorMessage: null, objectKey: 'x' }])
    }
    if (url === '/api/documents/doc-1/versions/dv-1/chunks') {
      return json([{ index: 58, text: '流是从支持数据处理操作的源生成的元素序列', title: '流', structurePath: 'ch3' }])
    }
    return json([])
  })
  vi.stubGlobal('fetch', fetchMock)

  render(<EvidencePicker initial={[{ documentVersionId: 'dv-1', chunkIndexes: [58] }]} onSelect={() => {}} />)

  const chunk = await screen.findByRole('checkbox', { name: /#58/ })
  expect(chunk).toBeChecked()
  expect(screen.getByLabelText('知识库')).toHaveValue('kb-1')
  expect(screen.getByLabelText('文档')).toHaveValue('doc-1')
  expect(screen.getByLabelText('文档版本')).toHaveValue('dv-1')
})

test('无证据时不回显，保持选择器为空', async () => {
  const fetchMock = vi.fn((input: RequestInfo | URL) => {
    const url = String(input)
    const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    if (url === '/api/knowledge-bases') return json([])
    return json([])
  })
  vi.stubGlobal('fetch', fetchMock)

  render(<EvidencePicker onSelect={() => {}} />)

  expect(await screen.findByLabelText('知识库')).toHaveValue('')
})
