#!/bin/bash
set -e

APP_NAME="devops-ai-bootstrap-1.0.0.jar"
APP_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR_PATH="$APP_DIR/$APP_NAME"
PID_FILE="$APP_DIR/.app.pid"

if [ ! -f "$JAR_PATH" ]; then
    JAR_PATH="$APP_DIR/$APP_NAME"
fi

check_env() {
    if ! command -v java &>/dev/null; then
        echo "[错误] 未找到 Java 运行环境，请安装 JDK 8+"
        exit 1
    fi
    if [ ! -f "$JAR_PATH" ]; then
        echo "[错误] 未找到 JAR 包：$JAR_PATH"
        exit 1
    fi
    mkdir -p "$APP_DIR/data" "$APP_DIR/output" "$APP_DIR/logs"
}

print_header() {
    echo "============================================="
    echo "  devops-ai 文档生成服务 v1.0.0"
    echo "  启动中..."
    echo "============================================="
    echo "JAR: $JAR_PATH"
    echo "日志: $APP_DIR/logs/devops-ai.log"
    echo "数据: $APP_DIR/data"
    echo "输出: $APP_DIR/output"
    echo ""
    echo "访问地址：http://localhost:8070"
    echo "============================================="
}

start_daemon() {
    check_env
    if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
        echo "服务已在运行中，PID: $(cat "$PID_FILE")"
        exit 0
    fi
    print_header
    nohup java -Xms1g -Xmx1g \
        -Dfile.encoding=UTF-8 \
        -jar "$JAR_PATH" \
        > "$APP_DIR/logs/console.log" 2>&1 &
    echo $! > "$PID_FILE"
    echo "服务已后台启动，PID: $!"
    echo "查看日志：tail -f $APP_DIR/logs/devops-ai.log"
}

stop_daemon() {
    if [ ! -f "$PID_FILE" ]; then
        echo "服务未运行（未找到 PID 文件）"
        exit 0
    fi
    PID=$(cat "$PID_FILE")
    if kill -0 "$PID" 2>/dev/null; then
        echo "正在停止服务 (PID: $PID)..."
        kill "$PID"
        sleep 2
        if kill -0 "$PID" 2>/dev/null; then
            echo "等待超时，强制停止..."
            kill -9 "$PID" 2>/dev/null || true
        fi
        echo "服务已停止"
    else
        echo "服务未运行"
    fi
    rm -f "$PID_FILE"
}

status_daemon() {
    if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
        PID=$(cat "$PID_FILE")
        echo "服务运行中，PID: $PID"
        echo "端口: 8070"
        echo "访问: http://localhost:8070"
    else
        echo "服务未运行"
        [ -f "$PID_FILE" ] && rm -f "$PID_FILE"
    fi
}

case "${1:-console}" in
    console)
        check_env
        print_header
        exec java -Xms1g -Xmx1g \
            -Dfile.encoding=UTF-8 \
            -jar "$JAR_PATH"
        ;;
    start)
        start_daemon
        ;;
    stop)
        stop_daemon
        ;;
    restart)
        stop_daemon
        sleep 1
        start_daemon
        ;;
    status)
        status_daemon
        ;;
    *)
        echo "用法: $0 {console|start|stop|restart|status}"
        echo ""
        echo "  console  前台启动（默认，Ctrl+C 停止）"
        echo "  start    后台启动"
        echo "  stop     停止后台服务"
        echo "  restart  重启后台服务"
        echo "  status   查看运行状态"
        exit 1
        ;;
esac
