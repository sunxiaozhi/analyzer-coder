from java_ts_analyzer import JavaAnalyzer


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
