# 如何获取抖音 Cookie

## 📋 什么是 Cookie？

Cookie 是浏览器在登录网站后保存的一串字符，相当于"登录凭证"。
抖音 API 需要这个 Cookie 来确认你是登录用户，否则无法解析私密作品。

**好消息**：**未登录也可以解析大部分公开作品**，但加上 Cookie 后能：
- 解析更多作品（关注作者的作品、好友可见的作品）
- 获取更高清的视频源

---

## 🌐 方法一：电脑浏览器（最简单，推荐）

### 步骤 1：打开抖音网页版
在 **Chrome / Edge / Firefox** 浏览器中访问：
```
https://www.douyin.com/
```

### 步骤 2：登录账号（可选）
- 点击右上角"登录"
- 用抖音 APP 扫码登录
- 不登录也能获取 Cookie，但能解析的作品更少

### 步骤 3：打开开发者工具
按 **F12** 键（或右键 → 检查 / Inspect）

### 步骤 4：找到 Cookies 面板
- **Chrome / Edge**：顶部菜单 → **Application** → 左侧 **Cookies** → 点击 `https://www.douyin.com`
- **Firefox**：顶部菜单 → **Storage** → **Cookies** → `https://www.douyin.com`

### 步骤 5：复制 Cookie
**最简单的方法** —— 在 Console（控制台）执行下面这一行：

```javascript
document.cookie
```

按回车，会输出类似：
```
ttwid=1%7C...; msToken=abc123...; UIFID=def456...; passport_csrf_token=...
```

**全选复制这一整段**（从 `ttwid=` 开始到末尾）

### 步骤 6：粘贴到 App
1. 在我们这个 App 里点击"设置 Cookie"
2. 把刚才复制的那一大串粘贴进去
3. 点击"保存"

---

## 📱 方法二：手机浏览器（如果没有电脑）

### 步骤 1：用手机 Chrome / Edge / Safari 打开
```
https://www.douyin.com/
```

### 步骤 2：登录抖音

### 步骤 3：打开开发者工具
**Android Chrome**：
1. 地址栏输入 `chrome://inspect/#devices`
2. 用 USB 连接手机，开启 USB 调试
3. 在电脑上查看手机浏览器的 Cookie

**iOS Safari**：
- Mac 用户：Safari → 开发 → [你的手机] → douyin.com
- 需要 Mac + 数据线

> ⚠️ 手机端获取 Cookie 比较麻烦，**强烈推荐用电脑**

---

## 🛠 方法三：直接复制 Cookie 字符串（最精确）

### Chrome 详细步骤：

1. F12 → **Application** 标签
2. 左侧 **Storage** → **Cookies** → `https://www.douyin.com`
3. 看到一堆 Name/Value 列表
4. **手动拼接**：每个 Name=Value 之间用 `; `（分号+空格）连接

例：
```
ttwid=1%7CPwxxx; odin_tt=abcd; msToken=xyz123; UIFID=def456; passport_csrf_token=ghi789; ...
```

### 关键 Cookie（至少要有这些）

| Name | 作用 |
|------|------|
| `ttwid` | 必需，抖音主凭证 |
| `msToken` | 必需，用于 a_bogus 签名 |
| `UIFID` | 必需，用户标识 |
| `odin_tt` | 推荐 |
| `passport_csrf_token` | 推荐 |
| `sessionid` | 登录后才会有 |
| `uid_tt` | 登录后才会有 |

---

## ⚠️ 常见问题

### Q1：Cookie 多久会过期？
- 大约 **30 天**，过期后需要重新获取
- 频繁登录/退出会缩短有效期

### Q2：粘贴后还是解析失败？
1. 检查 Cookie 是否完整（**不要漏了分号**）
2. 检查 Cookie 是否过期（重新登录后再获取）
3. 不要带 `document.cookie` 输出时的引号

### Q3：`document.cookie` 输出为空？
- 这是正常的！现代网站很多 Cookie 设置了 `HttpOnly` 标志
- 解决方法：用方法三（手动拼接）或用开发者工具导出 Cookie

### Q4：用 Cookie 安全吗？
- ⚠️ Cookie **等同于账号密码**，泄露后他人可以冒充你登录
- **只在本机使用**，**不要上传到任何网盘/IM**
- **不要把 Cookie 发给别人**

### Q5：HttpOnly Cookie 怎么办？
打开 Chrome DevTools → F12 → **Application** → **Cookies** → 双击 Value 单元格复制单个值

或者使用这个 JavaScript 在 Console 里获取**所有 Cookie**（包括 HttpOnly）：
```javascript
// 仅 Chrome DevTools 可用
document.cookie
// 这个只能获取非 HttpOnly 的
```

---

## 🎯 最快路径（30 秒搞定）

1. Chrome 打开 `https://www.douyin.com/`
2. F12 → Console
3. 输入 `document.cookie` 回车
4. 全选复制输出
5. 粘贴到 App
6. 完成

如果 `document.cookie` 返回的 Cookie 太短（少于 5 个），请用**手动拼接**方法。

---

## 🔒 隐私承诺

我们这个 App：
- ✅ Cookie **仅保存在本地** SharedPreferences
- ✅ 不会上传到任何服务器
- ✅ 不会用于任何其他用途
- ✅ 退出 App 后仍然保留（直到清除或卸载）

⚠️ 仅供个人技术学习使用，禁止商用