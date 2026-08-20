/**
 * Issue #64：复核队列 / 运营提醒两页共用的队列分页状态。
 * 页码 0 起（与列表 API 的 page 口径一致）、页大小默认 20；
 * URL 筛选变化（filterKey 变化，含浏览器回退/前进）时回到第一页。
 * 页面仍可调用 setPage(0) 在写 URL 的同时同步复位（避免中间帧带旧页码发请求）。
 */

import { useEffect, useState } from 'react';

export interface QueuePagination {
  page: number;
  size: number;
  setPage: (page: number) => void;
  onPageChange: (nextPage: number, nextSize: number) => void;
}

export function useQueuePagination(filterKey: string): QueuePagination {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);

  useEffect(() => {
    setPage(0);
  }, [filterKey]);

  return {
    page,
    size,
    setPage,
    onPageChange: (nextPage, nextSize) => {
      setPage(nextPage - 1);
      setSize(nextSize);
    },
  };
}
