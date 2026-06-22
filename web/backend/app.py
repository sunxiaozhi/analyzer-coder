from __future__ import annotations

from flask import Flask

from web.backend.config import BackendConfig
from web.backend.errors import register_error_handlers
from web.backend.routes import api_bp
from web.backend.services import AnalyzerService


def create_app(config: type[BackendConfig] | None = None) -> Flask:
    app = Flask(__name__)
    app.config.from_object(config or BackendConfig)

    app.extensions["analyzer_service"] = AnalyzerService.from_config(app.config)
    app.register_blueprint(api_bp, url_prefix="/api")
    register_error_handlers(app)

    return app

if __name__ == "__main__":
    app = create_app()
    app.run(
        host=app.config["HOST"],
        port=app.config["PORT"],
        debug=app.config["DEBUG"],
    )
