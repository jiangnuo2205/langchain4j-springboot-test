import chromadb

# 连接到你的 chroma 服务
client = chromadb.HttpClient(host="localhost", port=8020)

# 看看有哪些 collection
print(client.list_collections())

# 方式 A：删除指定 collection
client.delete_collection(name="你的collection名字")

# 方式 B：清空所有 collection
for col in client.list_collections():
    client.delete_collection(name=col.name)

print("清空完成")