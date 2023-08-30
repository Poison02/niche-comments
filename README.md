<center>
  <img src="./assets/logo.png" alt="logo">
</center>

# 项目简介
该项目是一个H5格式的点评网站，基于SpringBoot、MyBatis-Plus、MySQL和Redis搭建而成，用于给各大商户平台
招揽客户，以及客户给商户评价的平台！
## 项目环境
本项目核心主要是Redis，是运用Redis各种数据类型的一个实战项目！

- SpringBoot：2.3.12.RELEASE
- MySQL：8.0.30
- Redis：7.0
- Redisson：3.16.3
- MyBatis-Plus：3.4.3
- Hutool：5.7.17

## 本地运行
- 后端
1. 导入数据库文件，在 `resources/db/comments.sql`
2. 修改 `application.yml` 文件的MySQL和Redis配置
3. 启动 `NicheCommentsApplication` 即可启动

- 前端</br>
启动前端只需要启动`frontend`目录下的`nginx`就可以

- 访问</br>
浏览器访问 `localhost:8080` 即可看到