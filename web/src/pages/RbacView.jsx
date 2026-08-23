import { Alert, Button, Card, Input, Select, Space, Table, Tabs, Tag } from 'antd'
import { TimeText } from '../components/common.jsx'

export default function RbacView({ user, users, roles, audit, newUname, setNewUname, newPass, setNewPass, newRole, setNewRole, handleCreateUser, handleRoleChange, handleDelUser }) {
  return (
    <Card title="用户与权限(RBAC,admin 可见)">
      {!user || user.role !== 'admin' ? <Alert type="info" message="需以 admin 登录后查看/管理用户、角色与审计日志。" /> : (
        <Tabs items={[
          { key: 'users', label: '用户管理', children: <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            <Space wrap><Input style={{ width: 140 }} value={newUname} onChange={(e) => setNewUname(e.target.value)} placeholder="新用户名" /><Input.Password style={{ width: 180 }} value={newPass} onChange={(e) => setNewPass(e.target.value)} placeholder="临时密码(≥12位)" /><Select value={newRole} style={{ width: 110 }} onChange={setNewRole} options={['analyst', 'ops', 'audit', 'admin'].map((r) => ({ value: r, label: r }))} /><Button type="primary" onClick={handleCreateUser}>新增用户</Button></Space>
            <Table rowKey="username" size="small" dataSource={users} columns={[{ title: '用户名', dataIndex: 'username' }, { title: '角色', dataIndex: 'role', render: (v) => <Tag color={v === 'admin' ? 'red' : 'blue'}>{v}</Tag> }, { title: '状态', dataIndex: 'status', render: (v) => <Tag color={v === 'active' ? 'green' : 'default'}>{v}</Tag> }, { title: '操作', render: (_, u) => <Space><Select size="small" value={u.role} style={{ width: 110 }} onChange={(v) => handleRoleChange(u.username, v)} options={['analyst', 'ops', 'audit', 'admin'].map((r) => ({ value: r, label: r }))} /><Button size="small" danger onClick={() => handleDelUser(u.username)}>删</Button></Space> }]} />
          </Space> },
          { key: 'roles', label: `角色矩阵(${roles.length})`, children: <Table rowKey="name" size="small" pagination={false} dataSource={roles} columns={[{ title: '角色', dataIndex: 'name', render: (v) => <Tag color="blue">{v}</Tag> }, { title: '权限', dataIndex: 'permissions', render: (p) => p.join(', ') }]} /> },
          { key: 'audit', label: `审计日志(${audit.length})`, children: <div style={{ maxHeight: 300, overflow: 'auto' }}>{audit.slice().reverse().map((a, i) => <div key={i} style={{ fontSize: 12, padding: '3px 0' }}><TimeText value={a.timestamp} /> {a.action} → {a.target}</div>)}</div> },
        ]} />
      )}
    </Card>
  )
}
