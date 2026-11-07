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

    def test_linear_tasks_require_the_complete_unique_solution(self):
        self.assertTrue(validate({'kind': 'linearSolution', 'equation': '2x-1=5-x',
                                  'solution': {'x': 2}})['passed'])
        self.assertTrue(validate({'kind': 'linearSolution', 'equation': r'\frac{x}{2}+1=2',
                                  'solution': {'x': 2}})['passed'])
        for equation, solution in [('x^2=4', {'x': 2}), ('x=x', {'x': 2}),
                                   ('x+1=x', {'x': 2}), ('x+y=2', {'x': 1, 'y': 1}),
                                   ('x=2', {'x': 2, 'y': 3}), ('x=2', {'x': 3})]:
            with self.subTest(equation=equation), self.assertRaises(ValueError):
                validate({'kind': 'linearSolution', 'equation': equation, 'solution': solution})

    def test_linear_tasks_keep_original_domain_and_restrictions(self):
        for equation, restrictions in [('(x^2-1)/(x-1)=2', []), ('x=2', ['x != 2'])]:
            with self.subTest(equation=equation), self.assertRaises(ValueError):
                validate({'kind': 'linearSolution', 'equation': equation,
                          'solution': {'x': 1 if not restrictions else 2}, 'restrictions': restrictions})

    def test_original_denominator_before_simplification(self):
        with self.assertRaisesRegex(ValueError, 'domain'):
            validate({'kind': 'solution', 'equation': '(x^2-9)/(x-3)=6', 'solution': {'x': 3}})

    def test_fraction_values_derive_exact_answers_from_conditions_only(self):
        for expression, answer in [('1/2+1/4', '3/4'), ('3/5+4/7', '41/35'),
                                   ('7/8-1/4', '5/8'), ('2/3*9/10', '3/5'),
                                   ('5/6/(10/9)', '3/4')]:
            with self.subTest(expression=expression):
                result = validate({'kind': 'expressionValue', 'expression': expression})
                self.assertTrue(result['passed'])
                self.assertEqual(result['answer'], answer)
                # A discarded model answer cannot influence derivation. The
                # new kind never treats an answer field as an assertion.
                self.assertEqual(validate({'kind': 'expressionValue', 'expression': expression,
                                           'answer': '999'})['answer'], answer)
                with self.assertRaises(ValueError):
                    validate({'kind': 'equivalence', 'expression': expression, 'answer': '999'})

    def test_derived_values_keep_original_domain_and_are_finite_real(self):
        item = {'kind': 'expressionValue', 'expression': '(x^2-9)/(x-3)', 'restrictions': ['x != 3']}
        self.assertEqual(validate(item)['answer'], 'x + 3')
        item['restrictions'] = []
        with self.assertRaisesRegex(ValueError, 'Missing domain'):
            validate(item)
        for expression in ['1/0', 'sqrt(-1)', 'log(0)', 'sqrt(x)', 'log(x)']:
            with self.subTest(expression=expression), self.assertRaises(ValueError):
                validate({'kind': 'expressionValue', 'expression': expression})

    def test_linear_values_derive_the_unique_root_without_a_proposed_answer(self):
        for equation, answer in [('2x-1=5-x', '2'), ('x/2+1=2', '2'), ('3x=1', '1/3')]:
            with self.subTest(equation=equation):
                result = validate({'kind': 'linearValue', 'equation': equation})
                self.assertEqual((result['variable'], result['answer']), ('x', answer))
                self.assertTrue(result['passed'])
        with self.assertRaisesRegex(ValueError, 'Declared variable'):
            validate({'kind': 'linearValue', 'equation': 'y=2', 'variable': 'x'})
        for equation, restrictions in [('x^2=4', []), ('x=x', []), ('x+1=x', []),
                                       ('x+y=2', []), ('(x^2-1)/(x-1)=2', []), ('x=2', ['x != 2'])]:
            with self.subTest(equation=equation), self.assertRaises(ValueError):
                validate({'kind': 'linearValue', 'equation': equation, 'restrictions': restrictions})

    def test_derived_document_results_keep_assertion_order_for_private_answers(self):
        result = verify_document({'qa': {'algebra': [
            {'kind': 'expressionValue', 'expression': '1/2+1/4'},
            {'kind': 'linearValue', 'equation': '3x=1'}]}})
        self.assertTrue(result['passed'])
        self.assertEqual(result['checked'], 2)
        self.assertEqual([(item['index'], item['answer']) for item in result['results']], [(0, '3/4'), (1, '1/3')])

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
