# Java Demo Spring Boot Project

Small Spring Boot REST service for user registration.

## Run

```powershell
mvn --settings settings.xml spring-boot:run
```

## Test

```powershell
mvn --settings settings.xml test
```

## API

Create a user:

```powershell
curl -X POST http://localhost:8080/api/users `
  -H "Content-Type: application/json" `
  -d "{\"phone\":\"13800138000\",\"name\":\"Alice\"}"
```

List users:

```powershell
curl http://localhost:8080/api/users
```

Find by phone:

```powershell
curl http://localhost:8080/api/users/13800138000
```

Search by name:

```powershell
curl "http://localhost:8080/api/users?name=alice"
```

Update a user's display name:

```powershell
curl -X PATCH http://localhost:8080/api/users/13800138000 `
  -H "Content-Type: application/json" `
  -d "{\"name\":\"Alice Chen\"}"
```

Delete a user:

```powershell
curl -X DELETE http://localhost:8080/api/users/13800138000
```
