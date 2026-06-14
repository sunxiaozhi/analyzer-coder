from java_analyzer import JavaAnalyzer, build_api_mapping, build_call_chains, build_endpoint_sql_flows


def test_analyze_source_extracts_java_structure() -> None:
    source = """
        package com.example;

        import java.util.List;

        @Deprecated
        public class Demo extends Base implements Runnable {
            @Named
            private String name = "demo";

            public Demo(String name) {
                this.name = name;
            }

            @Override
            public void run() {
                names();
            }

            public List<String> names() {
                return List.of(name);
            }
        }
    """

    result = JavaAnalyzer().analyze_source(source)

    assert result.package == "com.example"
    assert [item.name for item in result.imports] == ["java.util.List"]
    assert not result.has_error

    symbols = {(symbol.kind, symbol.name, symbol.enclosing_type) for symbol in result.symbols}
    assert ("class", "Demo", None) in symbols
    assert ("field", "name", "Demo") in symbols
    assert ("constructor", "Demo", "Demo") in symbols
    assert ("method", "names", "Demo") in symbols

    assert result.metrics is not None
    assert result.metrics.type_count == 1
    assert result.metrics.field_count == 1
    assert result.metrics.method_count == 3
    assert result.metrics.call_count == 2

    demo = result.types[0]
    assert demo.superclass == "Base"
    assert demo.interfaces == ("Runnable",)
    assert demo.annotations == ("@Deprecated",)

    field = result.fields[0]
    assert field.type == "String"
    assert field.initializer == '"demo"'
    assert field.annotations == ("@Named",)

    run_method = next(method for method in result.methods if method.name == "run")
    assert run_method.return_type == "void"
    assert run_method.annotations == ("@Override",)

    constructor = next(method for method in result.methods if method.kind == "constructor")
    assert [(param.type, param.name) for param in constructor.parameters] == [("String", "name")]


def test_analyze_source_detects_spring_and_mybatis_surfaces() -> None:
    source = """
        package com.example;

        @RestController
        @RequestMapping("/api/users")
        class UserController {
            @GetMapping("/{id}")
            User getUser(String id) {
                return service.findById(id);
            }
        }

        @Service
        class UserService {
        }

        @Mapper
        interface UserMapper {
            @Select("select * from users where phone = #{phone}")
            User findByPhone(String phone);
        }
    """

    result = JavaAnalyzer().analyze_source(source)

    components = {(item.kind, item.name) for item in result.components}
    assert ("rest_controller", "UserController") in components
    assert ("service", "UserService") in components
    assert ("mapper", "UserMapper") in components

    endpoint = result.endpoints[0]
    assert endpoint.path == "/api/users/{id}"
    assert endpoint.http_methods == ("GET",)
    assert endpoint.enclosing_type == "UserController"
    assert endpoint.method_name == "getUser"

    sql_reference = result.sql_references[0]
    assert sql_reference.operation == "select"
    assert sql_reference.enclosing_type == "UserMapper"
    assert sql_reference.method_name == "findByPhone"
    assert "select * from users" in sql_reference.statement

    assert result.metrics is not None
    assert result.metrics.component_count == 3
    assert result.metrics.endpoint_count == 1
    assert result.metrics.sql_reference_count == 1


def test_analyze_source_extracts_richer_endpoint_and_call_data() -> None:
    source = """
        package com.example;

        @RestController
        @RequestMapping(value = {"/api/users", "/v1/users"})
        class UserController {
            private UserService service = new UserService();

            @GetMapping(path = "/{id}", produces = "application/json")
            User getUser(
                @PathVariable("id") String id,
                @RequestParam(name = "active", required = false, defaultValue = "true") boolean active,
                @RequestBody UserRequest request
            ) throws IOException {
                var user = new User(id);
                return service.findById(id, active).orElseThrow(() -> new RuntimeException("missing"));
            }
        }
    """

    result = JavaAnalyzer().analyze_source(source)

    controller = result.types[0]
    request_mapping = next(item for item in controller.annotation_details if item.name == "RequestMapping")
    assert request_mapping.arguments["value"] == ("/api/users", "/v1/users")

    get_user = next(method for method in result.methods if method.name == "getUser")
    assert get_user.throws == ("IOException",)
    assert get_user.annotation_details[0].arguments["path"] == ("/{id}",)
    assert get_user.annotation_details[0].arguments["produces"] == ("application/json",)

    assert {endpoint.path for endpoint in result.endpoints} == {
        "/api/users/{id}",
        "/v1/users/{id}",
    }
    endpoint = next(endpoint for endpoint in result.endpoints if endpoint.path == "/api/users/{id}")
    assert [(parameter.name, parameter.source, parameter.alias) for parameter in endpoint.parameters] == [
        ("id", "path", "id"),
        ("active", "query", "active"),
        ("request", "body", None),
    ]
    active = endpoint.parameters[1]
    assert active.required is False
    assert active.default_value == "true"

    calls = {(call.kind, call.name, call.arguments) for call in result.calls}
    assert ("constructor", "UserService", ()) in calls
    assert ("constructor", "User", ("id",)) in calls
    assert ("method", "findById", ("id", "active")) in calls
    assert any(kind == "constructor" and name == "RuntimeException" for kind, name, _args in calls)


def test_analyze_source_extracts_method_body_semantics() -> None:
    source = """
        package com.example;

        import java.util.List;

        class RuleService {
            String normalize(List<String> names) {
                final String first = names.get(0);
                if (first.isBlank()) {
                    return "empty";
                }
                for (String name : names) {
                    if (name.length() > 3) {
                        return name.trim();
                    }
                }
                try {
                    return first.toLowerCase();
                } catch (RuntimeException ex) {
                    throw ex;
                }
            }
        }
    """

    result = JavaAnalyzer().analyze_source(source)

    local = result.local_variables[0]
    assert local.name == "first"
    assert local.type == "String"
    assert local.initializer == "names.get(0)"
    assert local.modifiers == ("final",)
    assert local.enclosing_type == "RuleService"
    assert local.enclosing_method == "normalize"

    returns = [item.expression for item in result.returns]
    assert returns == ['"empty"', "name.trim()", "first.toLowerCase()"]

    controls = [(item.kind, item.condition) for item in result.control_structures]
    assert ("if", "first.isBlank()") in controls
    assert any(kind == "for_each" and condition and "name : names" in condition for kind, condition in controls)
    assert ("if", "name.length() > 3") in controls
    assert ("try", None) in controls
    assert any(kind == "catch" and condition and "RuntimeException ex" in condition for kind, condition in controls)
    assert ("throw", "ex") in controls

    assert result.metrics is not None
    assert result.metrics.local_variable_count == 1
    assert result.metrics.return_count == 3
    assert result.metrics.control_structure_count == 6


def test_build_call_chains_resolves_field_and_same_type_calls() -> None:
    source = """
        package com.example;

        @RestController
        class UserController {
            private UserService service;

            @PostMapping("/users")
            User create(UserRegistrationRequest request) {
                validate(request);
                return service.createUser(request);
            }

            void validate(UserRegistrationRequest request) {
                request.phone();
            }
        }

        @Service
        class UserService {
            private UserMapper mapper;

            User createUser(UserRegistrationRequest request) {
                return mapper.findByPhone(request.phone());
            }
        }

        @Mapper
        interface UserMapper {
            User findByPhone(String phone);
        }

        record UserRegistrationRequest(String phone) {
        }
    """

    result = JavaAnalyzer().analyze_source(source, file_path="UserController.java")
    chains = build_call_chains([result])
    chain_texts = [
        " -> ".join([chain.entrypoint.qualified_name, *(edge.callee.qualified_name for edge in chain.edges if edge.callee)])
        for chain in chains
    ]

    assert "UserController.create -> UserController.validate" in chain_texts
    assert "UserController.create -> UserService.createUser -> UserMapper.findByPhone" in chain_texts


def test_build_endpoint_sql_flows_links_endpoint_to_sql_and_tables(tmp_path) -> None:
    source = """
        package com.example;

        @RestController
        @RequestMapping("/api/users")
        class UserController {
            private UserService service;

            @GetMapping("/{id}")
            User getUser(String id) {
                return service.findById(id);
            }
        }

        @Service
        class UserService {
            private UserMapper mapper;

            User findById(String id) {
                return mapper.findById(id);
            }
        }

        @Mapper
        interface UserMapper {
            User findById(String id);
        }
    """
    mapper_xml = tmp_path / "UserMapper.xml"
    mapper_xml.write_text(
        """
        <mapper namespace="com.example.UserMapper">
          <select id="findById" resultType="User">
            select * from users where id = #{id}
          </select>
        </mapper>
        """,
        encoding="utf-8",
    )

    result = JavaAnalyzer().analyze_source(source, file_path="UserController.java")
    flows = build_endpoint_sql_flows([result], tmp_path)

    assert len(flows) == 1
    flow = flows[0]
    assert flow.endpoint_path == "/api/users/{id}"
    assert flow.code_path == ["UserController.getUser", "UserService.findById", "UserMapper.findById"]
    assert flow.sql.source == "xml"
    assert flow.sql.tables == ["users"]


def test_build_api_mapping_matches_vue_calls_to_spring_endpoints(tmp_path) -> None:
    frontend = tmp_path / "web" / "frontend" / "src"
    backend = tmp_path / "src" / "main" / "java" / "demo"
    frontend.mkdir(parents=True)
    backend.mkdir(parents=True)
    (frontend / "UserPanel.vue").write_text(
        """
        <script setup lang="ts">
        async function loadUser(id: string) {
          return await fetch(`/api/users/${id}`)
        }
        async function createUser(payload: unknown) {
          return await client.post('/api/users', payload)
        }
        async function deleteUser(id: string) {
          return await fetch(`/api/users/${id}`, { method: 'DELETE' })
        }
        </script>
        """,
        encoding="utf-8",
    )
    (backend / "UserController.java").write_text(
        """
        package demo;

        import org.springframework.web.bind.annotation.DeleteMapping;
        import org.springframework.web.bind.annotation.GetMapping;
        import org.springframework.web.bind.annotation.PostMapping;
        import org.springframework.web.bind.annotation.RequestMapping;
        import org.springframework.web.bind.annotation.RestController;

        @RestController
        @RequestMapping("/api/users")
        class UserController {
            @GetMapping("/{id}")
            User getUser(String id) {
                return new User(id);
            }

            @PostMapping
            User createUser() {
                return new User("new");
            }

            @DeleteMapping("/{id}")
            void deleteUser(String id) {
            }
        }

        record User(String id) {
        }
        """,
        encoding="utf-8",
    )

    result = build_api_mapping(
        tmp_path,
        frontend_path="web/frontend/src",
        backend_path="src/main/java",
    )

    assert result.summary["frontendCalls"] == 3
    assert result.summary["backendEndpoints"] == 3
    assert result.summary["matched"] == 3
    assert {(item.frontend.method, item.backend.handler if item.backend else "") for item in result.mappings} == {
        ("GET", "getUser"),
        ("POST", "createUser"),
        ("DELETE", "deleteUser"),
    }
