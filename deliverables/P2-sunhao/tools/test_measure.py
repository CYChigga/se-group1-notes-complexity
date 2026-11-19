"""AI编写并执行的边界测试，不代表人工审定。"""
import unittest
from measure import declarations,token_signature,occupied,tokens

class MeasurementTests(unittest.TestCase):
    def test_comments_literals_and_braces(self):
        s='class A {\n void f() {\n String s="} // not comment"; // ignore {\n /* multi\n line */\n if(true) { s="x"; }\n }\n}'
        rows,_=declarations(s,'A.java');self.assertEqual(len(rows),1)
        self.assertEqual((rows[0]['start'],rows[0]['end'],rows[0]['sloc']),(2,7,4))
    def test_overloads_and_constructors(self):
        rows,_=declarations('class A { A(){} void f(){} void f(int n){} }','A.java')
        self.assertEqual([r['signature'] for r in rows],['A()','f()','f(int)'])
        self.assertEqual([r['kind'] for r in rows],['constructor','method','method'])
    def test_interface_excluded_and_empty_body_included(self):
        rows,_=declarations('interface I { void noBody(); } class A { void empty(){} }','A.java')
        self.assertEqual([r['method'] for r in rows],['empty'])
    def test_anonymous_and_named_inner_class(self):
        rows,_=declarations('class A { void f(){ new Runnable(){ public void run(){} }; } class B { void b(){} } }','A.java')
        self.assertEqual(len(rows),3)
        self.assertTrue(any('/anonymous@f' in r['owner'] for r in rows))
        self.assertTrue(any(r['owner']=='A.B' for r in rows))
    def test_operators_do_not_read_string_content(self):
        rows,_=declarations('class A { String f(){ return "if && >"; } }','A.java')
        self.assertNotIn('&&',rows[0]['operators']);self.assertNotIn('if',rows[0]['operators'])
    def test_comments_preserve_semantics(self):
        a='class A { int f(){return 1;} }'
        b='// 中文\nclass A { /* 中文 */ int f(){return 1;} }'
        self.assertEqual(token_signature(a),token_signature(b))
    def test_multiline_declaration(self):
        s='class A {\n int f(\n int x) throws Exception {\n return x;\n }\n}'
        r,_=declarations(s,'A.java');self.assertEqual(r[0]['sloc'],4)

if __name__=='__main__':unittest.main(verbosity=2)
