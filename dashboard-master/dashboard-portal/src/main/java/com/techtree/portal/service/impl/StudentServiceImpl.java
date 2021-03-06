package com.techtree.portal.service.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.techtree.common.exception.Assert;
import com.techtree.common.service.RedisService;
import com.techtree.portal.mapper.CourseMapper;
import com.techtree.portal.mapper.SCMapper;
import com.techtree.portal.mapper.StudentMapper;
import com.techtree.portal.model.DO.Course;
import com.techtree.portal.model.DO.Student;
import com.techtree.portal.model.DO.StudentCourseRelation;
import com.techtree.portal.model.VO.CourseInfoVo;
import com.techtree.portal.model.VO.StudentAuthVo;
import com.techtree.portal.model.VO.StudentInfoVo;
import com.techtree.portal.model.VO.StudentTokenVo;
import com.techtree.portal.service.StudentService;
import com.techtree.portal.util.JwtUtil;
import com.techtree.portal.util.MailServiceUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class StudentServiceImpl extends ServiceImpl<StudentMapper, Student> implements StudentService {

    @Resource
    private StudentMapper studentMapper;
    @Resource
    private JwtUtil jwtUtil;
    @Resource
    private MailServiceUtil mailServiceUtil;
    @Resource
    private RedisService redisService;
    @Autowired
    private SCMapper scMapper;
    @Autowired
    private CourseMapper courseMapper;

    @Override
    public List<StudentInfoVo> getAllStudents() {
        List<Student> students = studentMapper.selectList(null);
        List<StudentInfoVo> studentInfoVos = new ArrayList<>();
        for (Student student : students) {
            studentInfoVos.add(new StudentInfoVo(student.getId(), student.getName(), student.getSex(), student.getEmail(), student.getMajor()));
        }
        return studentInfoVos;
    }

    @Override
    public StudentInfoVo getStudentById(long id) {
        Student student = studentMapper.selectById(id);
        StudentInfoVo studentInfoVo = new StudentInfoVo(student.getId(), student.getName(), student.getSex(), student.getEmail(), student.getMajor());
        if (ObjectUtil.isNull(student)) {
            log.error("??????id??? {} ????????????????????????", id);
            Assert.fail("?????????????????????");
        }
        return studentInfoVo;
    }

    @Override
    public StudentInfoVo getStudentByName(String name) {
        Student student = this.getOne(new QueryWrapper<Student>().eq("name", name));
        StudentInfoVo studentInfoVo = new StudentInfoVo(student.getId(), student.getName(), student.getSex(), student.getEmail(), student.getMajor());
        if (ObjectUtil.isNull(student)) {
            log.error("??????name??? {} ????????????????????????", name);
            Assert.fail("?????????????????????");
        }
        return studentInfoVo;
    }

    @Override
    public StudentInfoVo getStudentByEmail(String email) {
        Student student = this.getOne(new QueryWrapper<Student>().eq("email", email));
        StudentInfoVo studentInfoVo = new StudentInfoVo(student.getId(), student.getName(), student.getSex(), student.getEmail(), student.getMajor());
        if (ObjectUtil.isNull(student)) {
            log.error("??????email??? {} ????????????????????????", email);
            Assert.fail("?????????????????????");
        }
        return studentInfoVo;
    }


    @Override
    public boolean checkEmailExist(String email) {
        log.debug("??????????????????????????????");
        Student student =  this.getOne(new QueryWrapper<Student>().eq("email", email));
        log.debug("??????{}??????{}??????", email, student);
        if (ObjectUtil.isNull(student)) {
            return false;
        }
        return true;
    }

    @Override
    public int addStudent(Student student) {
        student.setPassword(BCrypt.hashpw(student.getPassword()));
        log.debug("???????????????????????????{}", student);
        int insert = studentMapper.insert(student);
        if(insert == 0) {
            log.error("?????????????????? {} ?????????", student);
            Assert.fail("????????????????????????");
        }
        return insert;
    }

    @Override
    public boolean updateStudent(StudentInfoVo student) {
        Student student1 = new Student(student.getId(), student.getName(), student.getSex(), student.getMajor());
        boolean update = this.update(student1, new UpdateWrapper<Student>().eq("id", student.getId()));
        if(!update) {
            log.error("?????????????????? {} ?????????", student);
            Assert.fail("????????????????????????");
        }
        return true;
    }

    @Override
    public int deleteStudentById(long id) {
        int deleteById = studentMapper.deleteById(id);
        if(deleteById == 0) {
            log.error("??????id??? {} ???????????????", id);
            Assert.fail("????????????????????????");
        }
        return deleteById;
    }

    @Override
    public StudentTokenVo login(String email, String password) {
        Student result = this.getOne(new QueryWrapper<Student>().eq("email", email));
        if(ObjectUtil.isNull(result)) {
            log.error("??????email??? {} ????????????????????????", email);
            Assert.fail("?????????????????????????????????");
        }
        boolean checkPassword = BCrypt.checkpw(password, result.getPassword());
        if(!checkPassword) {
            log.error("????????? {} ??? ?????? {} ?????????", email, password);
            Assert.fail("??????????????????????????????????????????");
        }
        String token = jwtUtil.createToken(email, result.getName());
        StudentTokenVo studentTokenVo = new StudentTokenVo();
        studentTokenVo.setStudent(new StudentInfoVo(result.getId(), result.getName(), result.getSex(), result.getEmail(), result.getMajor()));
        studentTokenVo.setAccessToken(token);
        return studentTokenVo;
    }

    @Override
    public int registry(StudentAuthVo student) {
        boolean emailExist = this.checkEmailExist(student.getEmail());
        log.info("????????????{}??????????????????:{}", student.getEmail(), emailExist);
        if(emailExist) {
            log.error("??????{}????????????", student.getEmail());
            Assert.fail("?????????????????????");
        }
        int addStudent;
        String redisCode = (String) redisService.get(student.getEmail());
        if(ObjectUtil.isNull(redisCode)) {
            Assert.fail("??????????????????");
        }
        if (redisCode.equals(student.getVerifyCode())) {
            Student newStudent = new Student(student.getId(), student.getPassword(), student.getEmail(), student.getName(), student.getSex(), student.getAge());
            addStudent = this.addStudent(newStudent);
        } else {
            addStudent = 0;
            Assert.fail("???????????????");
        }
        return addStudent;
    }

    @Override
    public boolean sendEmail(String to) {
        try {
            String code = String.valueOf((int)((Math.random() * 9 + 1) * 100000));
            mailServiceUtil.sendMail("support@techtree.tech", to, "Verify Code", code);
            redisService.set(to, code, 300);
        } catch (Exception e) {
            Assert.fail("??????????????????");
        }
        return true;
    }

    @Override
    public List<StudentCourseRelation> getStudentCourses(long id) {
        List<StudentCourseRelation> sid = scMapper.selectList(new QueryWrapper<StudentCourseRelation>().eq("sid", id));
        if (sid.isEmpty()) {
            log.error("??????id??? {} ????????????????????????", id);
            Assert.fail("?????????????????????");
        }
        return sid;
    }


    @Override
    public List<CourseInfoVo> getAllCoursesSelectByStudent(long id) {
        ArrayList<CourseInfoVo> courseInfoVos = new ArrayList<>();
        ArrayList<String> allCourseIds = new ArrayList<>();
        List<Course> courses = courseMapper.selectList(null);
        List<StudentCourseRelation> studentCourseRelations = scMapper.selectScByStudentId(id);
        for (StudentCourseRelation course : studentCourseRelations) {
            allCourseIds.add(course.getCid());
        }
        for (Course course : courses) {
            CourseInfoVo courseInfoVo = null;
            if(allCourseIds.contains(course.getCid())) {
                courseInfoVo = new CourseInfoVo(course.getCid(), course.getCname(), course.getTname(), course.getPlace(), course.getMajor(), course.getCapacity(), true);
            } else {
                courseInfoVo = new CourseInfoVo(course.getCid(), course.getCname(), course.getTname(), course.getPlace(), course.getMajor(), course.getCapacity(), false);
            }
            courseInfoVos.add(courseInfoVo);
        }
        return courseInfoVos;
    }

    @Override
    public boolean selectCourse(String cid, long sid) {
        Course course = courseMapper.selectById(cid);
        if(ObjectUtil.isNull(course)) {
            log.error("???id???{}????????????{}????????????: ??????????????????", sid, cid);
            Assert.fail("????????????");
        }
        if(course.getRemains().equals(0)) {
            log.error("???id???{}????????????{}????????????: ??????????????????", sid, cid);
            Assert.fail("????????????");
        }
        StudentCourseRelation studentCourseRelation = scMapper.selectScBy2Ids(sid, cid);
        if(!ObjectUtil.isNull(studentCourseRelation)) {
            log.error("???id???{}????????????{}????????????: ????????????", sid, cid);
            Assert.fail("????????????");
        }
        int insert = scMapper.insert(new StudentCourseRelation(sid, cid));
        int reduceRemain = scMapper.reduceRemain(cid);
        if(insert==1 && reduceRemain==1)    return true;
        else {
            log.error("???id???{}????????????{}????????????", sid, cid);
            Assert.fail("????????????");
            return false;
        }
    }

    @Override
    public boolean withdrawCourse(String cid, long sid) {
        Course course = courseMapper.selectById(cid);
        if(ObjectUtil.isNull(course)) {
            log.error("???id???{}????????????{}????????????: ??????????????????", sid, cid);
            Assert.fail("????????????");
        }
        if(course.getCapacity().equals(0)) {
            log.error("???id???{}????????????{}????????????: ???????????????????????????", sid, cid);
            Assert.fail("????????????");
        }
        StudentCourseRelation studentCourseRelation = scMapper.selectScBy2Ids(sid, cid);
        if(ObjectUtil.isNull(studentCourseRelation)) {
            log.error("???id???{}????????????{}????????????: ????????????", sid, cid);
            Assert.fail("????????????");
        }
        int deleteCourse = scMapper.deleteCourse(sid, cid);
        int increaseRemain = scMapper.increaseRemain(cid);
        if(deleteCourse==1 && increaseRemain==1)    return true;
        else {
            log.error("???id???{}????????????{}????????????", sid, cid);
            Assert.fail("????????????");
            return false;
        }
    }


}
