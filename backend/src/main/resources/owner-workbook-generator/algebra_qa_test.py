import unittest
from algebra_qa import validate, verify_document, parse


class AlgebraTests(unittest.TestCase):
    def test_binomial_equivalence(self):
        self.assertTrue(validate({'kind': 'equivalence', 'expression': '(x+3)^2', 'answer': 'x^2+6x+9'})['passed'])

    def test_tex_fraction_and_domain(self):
        item = {'kind': 'equivalence', 'expression': r'\frac{x^2-9}{x-3}', 'answer': 'x+3', 'restrictions': ['x != 3']}
        self.assertTrue(validate(item)['passed'])
        item['restrictions'] = []
        with self.assertRaisesRegex(ValueError, 'Missing domain'):
            validate(item)

    def test_correct_and_incorrect_substitution(self):
        item = {'kind': 'solution', 'equation': '2x-1=5-x', 'solution': {'x': 2}}
        self.assertTrue(validate(item)['passed'])
        item['solution']['x'] = 3
        with self.assertRaises(ValueError):
            validate(item)

    def test_original_denominator_before_simplification(self):
        with self.assertRaisesRegex(ValueError, 'domain'):
            validate({'kind': 'solution', 'equation': '(x^2-9)/(x-3)=6', 'solution': {'x': 3}})

    def test_rejects_wrong_equivalence(self):
        with self.assertRaises(ValueError):
            validate({'kind': 'equivalence', 'expression': '(x+3)^2', 'answer': 'x^2+9'})

    def test_safe_parser_and_bounds(self):
        for expression in ["__import__('os').system('whoami')", 'x.__class__', '2^100000', 'x^x', '(((((2^12)^12)^12)^12)^12)', '((x^12)^12)^12', '(' * 100 + 'x' + ')' * 100]:
            with self.subTest(expression=expression), self.assertRaises(ValueError):
                parse(expression)

    def test_rejects_empty_domain_disguised_as_restriction(self):
        for restriction in ['0 != 0', 'x-x != 0']:
            with self.assertRaises(ValueError):
                validate({'kind': 'equivalence', 'expression': '(x^2-9)/(x-3)', 'answer': 'x+3', 'restrictions': [restriction]})

    def test_nested_assertion_and_missing_coverage(self):
        self.assertEqual(verify_document({})['checked'], 0)
        result = verify_document({'teacherPages': [{'blocks': [{'type': 'grid', 'children': [
            {'validation': {'kind': 'solution', 'equation': 'x=2', 'solution': {'x': 2}}}]}]}]})
        self.assertEqual(result['checked'], 1)
        self.assertTrue(result['passed'])


if __name__ == '__main__':
    unittest.main()
