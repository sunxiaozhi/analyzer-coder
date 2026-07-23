export interface PageResult<T> {
  items: T[];
  pageNum: number;
  pageSize: number;
  total: number;
  pages: number;
}
