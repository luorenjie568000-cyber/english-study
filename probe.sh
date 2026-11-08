echo "=== 1) 加载 4B 模型"
LS=$(ls -l /data/data/com.englishstudy.app/files/llm/qwen3-4b.gguf | awk '{print $5}')
echo "model bytes: $LS (expect 2497281120)"
echo "=== 2) 内存"
free -m | head -2
echo "=== 3) 进程"
ps -A | grep -c englishstudy
