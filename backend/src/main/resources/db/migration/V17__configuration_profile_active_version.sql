-- 配置版本「当前生效」：知识管理员显式把某个已发布版本标记为在线问答的生效配置。
-- 与 IndexRelease 的「设为当前」语义一致：评测仍可显式指定任意版本做对比，
-- 在线问答只读 active 版本（未设置时回退 ProfileDefaults）。
ALTER TABLE configuration_profile ADD COLUMN active_version_no INTEGER;
