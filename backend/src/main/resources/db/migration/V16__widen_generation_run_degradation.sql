-- 放宽 degradation 列：检索降级消息拼接 OpenSearch 异常 message 后可能超过 100 字符，
-- 导致 recordGeneration 写库报 value too long，把一次本已成功的问答误判为 run.failed。
-- （根因：embedding 维度 128 与索引 knn_vector 1024 不匹配时 vector 检索失败产生 degradation，
--   该 degradation 在生成完成后的指标回填时写库超长。）
ALTER TABLE generation_run ALTER COLUMN degradation TYPE VARCHAR(2000);
