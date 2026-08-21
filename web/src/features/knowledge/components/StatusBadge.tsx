const LABELS: Record<string, string> = {
  UPLOADED: '已上传', PROCESSING: '处理中', READY: '就绪', FAILED: '失败', OFFLINE: '离线',
  DRAFT: '草稿', PUBLISHING: '发布中', PUBLISHED: '已发布', ROLLED_BACK: '已回滚',
}

export function StatusBadge({ status }: { status: string }) {
  return <span className={`status-badge status-${status.toLowerCase()}`}>{LABELS[status] ?? status}</span>
}
