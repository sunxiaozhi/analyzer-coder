from __future__ import annotations

# 默认模板只作为新项目的初始化回退；用户数据仍保存到 MySQL。
DEFAULT_KB_TEMPLATES = [
    {
        "id": "domain",
        "name": "业务规则",
        "path": "domain/user-registration.md",
        "content": """# 用户注册

## 业务规则
- 手机号必须唯一

## 相关接口
- POST /api/users/register

## 相关代码
- UserController.register
- UserService.createUser

## 边界条件
- 手机号为空时拒绝
- 手机号已存在时返回业务错误
""",
    },
    {
        "id": "api",
        "name": "接口说明",
        "path": "api/user-api.md",
        "content": """# 用户接口

## 接口
- POST /api/users/register

## 请求
- phone: 用户手机号

## 响应
- 注册成功返回用户信息
- 手机号重复返回业务错误
""",
    },
    {
        "id": "decision",
        "name": "决策记录",
        "path": "decisions/phone-unique-rule.md",
        "content": """# 手机号唯一规则

## 决策
注册以手机号作为唯一身份标识。

## 原因
- 降低重复账户风险
- 便于后续登录和通知流程

## 影响范围
- 用户注册
- 用户资料更新
""",
    },
    {
        "id": "troubleshooting",
        "name": "故障排查",
        "path": "troubleshooting/registration-errors.md",
        "content": """# 注册错误排查

## 现象
用户注册失败。

## 排查
- 检查手机号是否已存在
- 检查注册接口是否返回业务错误
- 检查数据库唯一约束
""",
    },
]
