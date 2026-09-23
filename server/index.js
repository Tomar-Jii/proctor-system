const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const path = require('path');

const app = express();
const server = http.createServer(app);
const io = new Server(server, { cors: { origin: '*' } });

app.use(express.static(path.join(__dirname, 'public')));

const students = new Map();
const admins = new Set();

io.on('connection', (socket) => {
  socket.on('register', ({ role, id }) => {
    socket.role = role;
    socket.customId = id;

    if (role === 'student') {
      students.set(id, socket.id);
      io.to(Array.from(admins)).emit('student-list', Array.from(students.keys()));
    } else if (role === 'admin') {
      admins.add(socket.id);
      socket.emit('student-list', Array.from(students.keys()));
    }
  });

  socket.on('request-stream', ({ studentId }) => {
    const studentSocketId = students.get(studentId);
    if (studentSocketId) {
      io.to(studentSocketId).emit('start-stream', { adminSocketId: socket.id });
    }
  });

  socket.on('offer', ({ targetSocketId, sdp }) => {
    io.to(targetSocketId).emit('offer', { senderSocketId: socket.id, sdp });
  });

  socket.on('answer', ({ targetSocketId, sdp }) => {
    io.to(targetSocketId).emit('answer', { sdp });
  });

  socket.on('ice-candidate', ({ targetSocketId, candidate }) => {
    io.to(targetSocketId).emit('ice-candidate', { candidate });
  });

  socket.on('stop-stream', ({ studentId }) => {
    const studentSocketId = students.get(studentId);
    if (studentSocketId) {
      io.to(studentSocketId).emit('stop-stream');
    }
  });

  socket.on('disconnect', () => {
    if (socket.role === 'student') {
      students.delete(socket.customId);
      io.to(Array.from(admins)).emit('student-list', Array.from(students.keys()));
    } else if (socket.role === 'admin') {
      admins.delete(socket.id);
    }
  });
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, () => console.log(`Server live on port ${PORT}`));
